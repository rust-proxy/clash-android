use std::{
    collections::HashMap,
    net::Ipv4Addr,
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock},
    time::Duration,
};

use ipnet::Ipv4Net;
use localsend::{
    crypto::{
        cert::{SelfSignedCert, generate_self_signed},
        hash::sha256_hex,
    },
    discovery::{
        self, DeviceChannel, DeviceIdentity, DiscoveredDevice, DiscoveryConfig, DiscoveryEvent,
        DiscoveryHandle, HttpChannel,
    },
    http::{
        client::{ClientError, LsHttpClientV2},
        dto_v2::{PrepareUploadRequestDtoV2, RegisterDtoV2},
        server::{
            ServerConfigV2, ServerHandle, TlsConfig,
            common::save::FileUploadTarget,
            start_with_port,
            v2::{PrepareUploadDecisionV2, ServerEventV2},
            web::WebConfig,
        },
        state::ClientInfo,
    },
    model::{
        discovery::{DeviceType, PROTOCOL_VERSION_V2, ProtocolType},
        transfer::FileDto,
    },
    multicast::MulticastDevice,
    util::interface::InterfaceFilter,
};
use tokio::{
    io::AsyncReadExt,
    sync::{mpsc, oneshot},
};
use tokio_util::sync::CancellationToken;

const PORT: u16 = 53317;
const MAX_SIZE: u64 = 16 * 1024 * 1024;
const HTTPS: ProtocolType = ProtocolType::Https;

// Reuse one TLS identity across sessions in this process.
static IDENTITY: OnceLock<SelfSignedCert> = OnceLock::new();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum TransferError {
    #[error("LocalSend unavailable")]
    Unavailable,
    #[error("Transfer failed")]
    Failed,
    #[error("PIN required")]
    Pin,
    #[error("Transfer declined")]
    Declined,
    #[error("Unsupported file")]
    File,
}

impl From<ClientError> for TransferError {
    fn from(error: ClientError) -> Self {
        match error {
            ClientError::StatusCode(status) if status.status == 401 => Self::Pin,
            ClientError::StatusCode(status) if status.status == 403 => Self::Declined,
            _ => Self::Failed,
        }
    }
}

#[derive(uniffi::Record)]
pub struct Peer {
    pub fingerprint: String,
    pub alias: String,
    pub address: String,
}

#[derive(uniffi::Enum)]
pub enum TransferEvent {
    PeersChanged,
    Offer {
        id: String,
        alias: String,
        name: String,
        size: u64,
    },
    Withdrawn {
        id: String,
    },
    Received {
        name: String,
    },
}

type Decision = oneshot::Sender<PrepareUploadDecisionV2>;
struct Pending {
    id: String,
    file_id: String,
    decision: Decision,
}

struct Inputs {
    server: mpsc::Receiver<ServerEventV2>,
    discovery: mpsc::Receiver<DiscoveryEvent>,
}

#[derive(uniffi::Object)]
pub struct TransferSession {
    server: ServerHandle,
    discovery: DiscoveryHandle,
    inputs: tokio::sync::Mutex<Inputs>,
    info: RegisterDtoV2,
    network: Ipv4Net,
    receive_path: Option<PathBuf>,
    pending: Mutex<Option<Pending>>,
    stop: CancellationToken,
    stops: Mutex<Option<(oneshot::Sender<()>, oneshot::Sender<()>)>>,
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn open_transfer(
    alias: String,
    network: String,
    receive_path: Option<String>,
) -> Result<Arc<TransferSession>, TransferError> {
    let network: Ipv4Net = network.parse().map_err(|_| TransferError::Unavailable)?;
    let cert = tokio::task::spawn_blocking(|| {
        if IDENTITY.get().is_none() {
            let _ = IDENTITY.set(generate_self_signed().map_err(|_| TransferError::Unavailable)?);
        }
        IDENTITY.get().ok_or(TransferError::Unavailable)
    })
    .await
    .map_err(|_| TransferError::Unavailable)??;
    let (server_tx, server_rx) = mpsc::channel(16);
    let (end_discovery, discovery_stop) = oneshot::channel();
    let mut info = RegisterDtoV2 {
        alias,
        version: PROTOCOL_VERSION_V2.into(),
        device_model: None,
        device_type: Some(DeviceType::Mobile),
        fingerprint: cert.fingerprint.clone(),
        port: PORT,
        protocol: HTTPS,
        download: false,
    };
    let listen = |port, stop| {
        start_with_port(
            port,
            Some(TlsConfig {
                cert: cert.certificate_pem.clone(),
                private_key: cert.private_key_pem.clone(),
            }),
            ClientInfo {
                alias: info.alias.clone(),
                version: info.version.clone(),
                device_model: None,
                device_type: info.device_type.clone(),
                token: info.fingerprint.clone(),
            },
            None,
            Some(ServerConfigV2 {
                pin: None,
                verify_checksums: true,
                event_tx: server_tx.clone(),
            }),
            WebConfig::default(),
            stop,
        )
    };
    let (end_server, stop) = oneshot::channel();
    let (server, end_server) = match listen(PORT, stop).await {
        Ok(server) => (server, end_server),
        Err(e)
            if e.downcast_ref::<std::io::Error>()
                .is_some_and(|e| e.kind() == std::io::ErrorKind::AddrInUse) =>
        {
            // The standalone LocalSend app can be listening on the same phone.
            let (end_server, stop) = oneshot::channel();
            (
                listen(0, stop)
                    .await
                    .map_err(|_| TransferError::Unavailable)?,
                end_server,
            )
        }
        Err(_) => return Err(TransferError::Unavailable),
    };
    info.port = server.port();
    let (events, event_rx) = mpsc::channel(32);
    let discovery = discovery::start(
        DiscoveryConfig {
            group: Ipv4Addr::new(224, 0, 0, 167),
            group_v6: None,
            port: PORT,
            interface_filter: InterfaceFilter {
                whitelist: Some(vec![network.addr().to_string()]),
                blacklist: None,
            },
            device: MulticastDevice {
                alias: info.alias.clone(),
                version: info.version.clone(),
                device_model: None,
                device_type: info.device_type.clone(),
                fingerprint: info.fingerprint.clone(),
                port: server.port(),
                protocol: HTTPS,
                download: false,
            },
            identity: DeviceIdentity {
                cert_pem: cert.certificate_pem.clone(),
                private_key_pem: cert.private_key_pem.clone(),
            },
            timeout: Duration::from_secs(5),
            event_tx: Some(events),
        },
        discovery_stop,
    )
    .await;
    Ok(Arc::new(TransferSession {
        server,
        discovery,
        inputs: tokio::sync::Mutex::new(Inputs {
            server: server_rx,
            discovery: event_rx,
        }),
        info,
        network,
        receive_path: receive_path.map(PathBuf::from),
        pending: Mutex::new(None),
        stops: Mutex::new(Some((end_server, end_discovery))),
        stop: CancellationToken::new(),
    }))
}

#[uniffi::export(async_runtime = "tokio")]
impl TransferSession {
    pub fn port(&self) -> u16 {
        self.server.port()
    }

    pub async fn wait_stopped(&self) {
        self.server.wait_stopped().await;
        self.discovery.wait_stopped().await;
    }

    pub fn stop(&self) {
        self.stop.cancel();
        self.stops.lock().unwrap().take();
        self.pending.lock().unwrap().take();
    }

    pub async fn announce(&self) {
        self.discovery.announce().await;
    }

    pub async fn find_peer(&self, address: String, port: u16) -> Result<(), TransferError> {
        if !self.on_lan(&address) || port == 0 {
            return Err(TransferError::Unavailable);
        }
        self.discovery
            .discover(&address, port, HTTPS)
            .await
            .map_err(|_| TransferError::Unavailable)?
            .ok_or(TransferError::Unavailable)?;
        Ok(())
    }

    pub fn peers(&self) -> Vec<Peer> {
        self.discovery
            .devices()
            .into_iter()
            .filter_map(|entry| {
                let d = entry.device;
                let h = d.http()?;
                if !entry.confirmed || h.protocol != HTTPS || !self.on_lan(&h.host) {
                    return None;
                }
                Some(Peer {
                    address: h.host.clone(),
                    alias: d.alias,
                    fingerprint: d.fingerprint,
                })
            })
            .collect()
    }

    pub fn answer(&self, id: String, accept: bool) {
        let mut pending = self.pending.lock().unwrap();
        if let Some(p) = pending.take_if(|p| p.id == id) {
            let decision = if accept {
                PrepareUploadDecisionV2::Accept([p.file_id].into())
            } else {
                PrepareUploadDecisionV2::Decline
            };
            let _ = p.decision.send(decision);
        }
    }

    pub async fn next_event(&self) -> Result<TransferEvent, TransferError> {
        let mut inputs = self.inputs.lock().await;
        let Inputs { server, discovery } = &mut *inputs;
        loop {
            let event = tokio::select! {
                _=self.stop.cancelled()=>return Err(TransferError::Failed),
                Some(_)=discovery.recv()=>return Ok(TransferEvent::PeersChanged),
                event=server.recv()=>event.ok_or(TransferError::Unavailable)?,
            };
            match event {
                ServerEventV2::Register { ip, info }
                    if self.on_lan(&ip.to_string()) && info.protocol == HTTPS =>
                {
                    self.discovery
                        .observe_inbound(DiscoveredDevice {
                            alias: info.alias,
                            version: info.version,
                            device_model: info.device_model,
                            device_type: info.device_type.clone(),
                            fingerprint: info.fingerprint,
                            download: false,
                            channel: DeviceChannel::Http(HttpChannel {
                                host: ip.to_string(),
                                port: info.port,
                                protocol: HTTPS,
                            }),
                        })
                        .await;
                    return Ok(TransferEvent::PeersChanged);
                }
                ServerEventV2::PrepareUpload {
                    ip,
                    files,
                    info,
                    session_id,
                    decision_tx,
                    ..
                } => {
                    let mut pending = self.pending.lock().unwrap();
                    if self.receive_path.is_some()
                        && self.on_lan(&ip.to_string())
                        && pending.is_none()
                        && files.len() == 1
                        && let Some((file_id, file)) = files.into_iter().next()
                        && (1..=MAX_SIZE).contains(&file.size)
                    {
                        *pending = Some(Pending {
                            id: session_id.clone(),
                            file_id,
                            decision: decision_tx,
                        });
                        return Ok(TransferEvent::Offer {
                            id: session_id,
                            alias: info.alias,
                            name: file.file_name,
                            size: file.size,
                        });
                    }
                    let _ = decision_tx.send(PrepareUploadDecisionV2::Decline);
                }
                ServerEventV2::PrepareUploadAborted { session_id } => {
                    self.pending.lock().unwrap().take_if(|p| p.id == session_id);
                    return Ok(TransferEvent::Withdrawn { id: session_id });
                }
                ServerEventV2::FileUpload {
                    target_tx, file, ..
                } => {
                    let path = self.receive_path.clone().ok_or(TransferError::Failed)?;
                    let (tx, rx) = oneshot::channel();
                    target_tx
                        .send(FileUploadTarget::Path {
                            path,
                            result_tx: tx,
                            progress_tx: None,
                        })
                        .map_err(|_| TransferError::Failed)?;
                    tokio::select! {
                        _=self.stop.cancelled()=>return Err(TransferError::Failed),
                        result=rx=>result.map_err(|_|TransferError::Failed)?.map_err(|_|TransferError::Failed)?,
                    }
                    return Ok(TransferEvent::Received {
                        name: file.file_name,
                    });
                }
                ServerEventV2::SessionEnd {
                    session_id,
                    reason: localsend::http::server::v2::SessionEndReasonV2::Cancelled,
                } => return Ok(TransferEvent::Withdrawn { id: session_id }),
                ServerEventV2::ListenerFailed { .. } => return Err(TransferError::Unavailable),
                _ => {}
            }
        }
    }

    pub async fn send(
        &self,
        fingerprint: String,
        path: String,
        name: String,
        pin: Option<String>,
    ) -> Result<(), TransferError> {
        let peer = self
            .discovery
            .device_by_fingerprint(&fingerprint)
            .filter(|p| p.confirmed)
            .ok_or(TransferError::Unavailable)?;
        let h = peer
            .device
            .http()
            .filter(|h| h.protocol == HTTPS && self.on_lan(&h.host))
            .ok_or(TransferError::Unavailable)?;
        let file = tokio::fs::File::open(path)
            .await
            .map_err(|_| TransferError::File)?;
        let mut bytes = Vec::new();
        file.take(MAX_SIZE + 1)
            .read_to_end(&mut bytes)
            .await
            .map_err(|_| TransferError::File)?;
        if bytes.is_empty() || bytes.len() as u64 > MAX_SIZE {
            return Err(TransferError::File);
        }
        let cert = IDENTITY.get().ok_or(TransferError::Unavailable)?;
        let client = LsHttpClientV2::try_new(
            &cert.private_key_pem,
            &cert.certificate_pem,
            Some(fingerprint),
            Some(Duration::from_secs(120)),
        )?;
        let file = FileDto {
            id: "profile".into(),
            file_name: name,
            size: bytes.len() as u64,
            file_type: "application/yaml".into(),
            sha256: Some(sha256_hex(&bytes)),
            preview: None,
            metadata: None,
        };
        let request = PrepareUploadRequestDtoV2 {
            info: self.info.clone(),
            files: HashMap::from([(file.id.clone(), file)]),
        };
        let prepared = client
            .prepare_upload(
                HTTPS,
                &h.host,
                h.port,
                None,
                request,
                pin.as_deref(),
                self.stop.clone(),
            )
            .await?
            .response
            .ok_or(TransferError::Declined)?;
        let token = prepared
            .files
            .get("profile")
            .ok_or(TransferError::Declined)?;
        client
            .upload(
                HTTPS,
                &h.host,
                h.port,
                None,
                &prepared.session_id,
                "profile",
                token,
                bytes.into(),
                self.stop.clone(),
            )
            .await?;
        Ok(())
    }
}

impl TransferSession {
    fn on_lan(&self, host: &str) -> bool {
        host.parse::<Ipv4Addr>().is_ok_and(|ip| {
            self.network.contains(&ip)
                && ip != self.network.addr()
                && !ip.is_multicast()
                && !ip.is_unspecified()
        })
    }
}
