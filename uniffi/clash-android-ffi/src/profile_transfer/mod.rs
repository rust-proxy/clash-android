mod payload;

use std::{
    net::Ipv4Addr,
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock},
    time::Duration,
};

use self::payload::{MAX_SIZE, Offer, file_info, subscription_name, subscription_url};
use ipnet::Ipv4Net;
use localsend::{
    crypto::cert::{SelfSignedCert, generate_self_signed},
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
    model::discovery::{DeviceType, PROTOCOL_VERSION_V2, ProtocolType},
    multicast::MulticastDevice,
    util::interface::InterfaceFilter,
};
use tokio::{
    io::AsyncReadExt,
    sync::{mpsc, oneshot},
};
use tokio_util::sync::CancellationToken;

const PORT: u16 = 53317;
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
    #[error("Invalid subscription URL")]
    Subscription,
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
        has_subscription: bool,
    },
    Withdrawn {
        id: String,
    },
    Received {
        name: String,
        url: Option<String>,
    },
}

type Decision = oneshot::Sender<PrepareUploadDecisionV2>;
struct Pending {
    id: String,
    offer: Offer,
    decision: Option<Decision>,
    received_profile: bool,
    received_url: Option<String>,
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
        let Some(p) = pending.as_mut().filter(|p| p.id == id) else {
            return;
        };
        let Some(tx) = p.decision.take() else {
            return;
        };
        if accept {
            let ids = std::iter::once(p.offer.profile_id.clone())
                .chain(p.offer.url_id.clone())
                .collect();
            if tx.send(PrepareUploadDecisionV2::Accept(ids)).is_ok() {
                return;
            }
        } else {
            let _ = tx.send(PrepareUploadDecisionV2::Decline);
        }
        pending.take();
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
                        && let Some(offer) = Offer::parse(&files)
                    {
                        let event = TransferEvent::Offer {
                            id: session_id.clone(),
                            alias: info.alias,
                            name: offer.name.clone(),
                            size: offer.size,
                            has_subscription: offer.url_id.is_some(),
                        };
                        *pending = Some(Pending {
                            id: session_id,
                            offer,
                            decision: Some(decision_tx),
                            received_profile: false,
                            received_url: None,
                        });
                        return Ok(event);
                    }
                    let _ = decision_tx.send(PrepareUploadDecisionV2::Decline);
                }
                ServerEventV2::PrepareUploadAborted { session_id } => {
                    self.pending.lock().unwrap().take_if(|p| p.id == session_id);
                    return Ok(TransferEvent::Withdrawn { id: session_id });
                }
                ServerEventV2::FileUpload {
                    target_tx,
                    file_id,
                    session_id,
                    ..
                } => {
                    let is_url = {
                        let pending = self.pending.lock().unwrap();
                        let Some(p) = pending
                            .as_ref()
                            .filter(|p| p.id == session_id && p.decision.is_none())
                        else {
                            continue;
                        };
                        if file_id == p.offer.profile_id {
                            false
                        } else if p.offer.url_id.as_ref() == Some(&file_id) {
                            true
                        } else {
                            continue;
                        }
                    };
                    let scratch = self.receive_path.as_ref().ok_or(TransferError::Failed)?;
                    let url_path = if is_url {
                        let dir = scratch.parent().ok_or(TransferError::Failed)?;
                        Some(
                            tempfile::NamedTempFile::new_in(dir)
                                .map_err(|_| TransferError::Failed)?
                                .into_temp_path(),
                        )
                    } else {
                        None
                    };
                    let path = url_path
                        .as_ref()
                        .map_or_else(|| scratch.clone(), |p| p.to_path_buf());
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
                    let url = if let Some(path) = url_path {
                        Some(subscription_url(
                            &tokio::fs::read_to_string(&path)
                                .await
                                .map_err(|_| TransferError::Subscription)?,
                        )?)
                    } else {
                        None
                    };
                    let mut pending = self.pending.lock().unwrap();
                    let Some(p) = pending.as_mut().filter(|p| p.id == session_id) else {
                        continue;
                    };
                    if is_url {
                        p.received_url = url;
                    } else {
                        p.received_profile = true;
                    }
                    if p.received_profile && (p.offer.url_id.is_none() || p.received_url.is_some())
                    {
                        let completed = pending.take().unwrap();
                        return Ok(TransferEvent::Received {
                            name: completed.offer.name,
                            url: completed.received_url,
                        });
                    }
                }
                ServerEventV2::SessionEnd { session_id, .. } => {
                    if self
                        .pending
                        .lock()
                        .unwrap()
                        .take_if(|p| p.id == session_id)
                        .is_some()
                    {
                        return Ok(TransferEvent::Withdrawn { id: session_id });
                    }
                }
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
        url: Option<String>,
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
        let mut parts = Vec::with_capacity(2);
        if let Some(url) = url {
            let data = subscription_url(&url)?.into_bytes();
            parts.push((
                file_info(
                    "subscription",
                    subscription_name(&name),
                    "text/plain",
                    &data,
                ),
                data,
            ));
        }
        parts.push((
            file_info("profile", name, "application/yaml", &bytes),
            bytes,
        ));
        let request = PrepareUploadRequestDtoV2 {
            info: self.info.clone(),
            files: parts
                .iter()
                .map(|(f, _)| (f.id.clone(), f.clone()))
                .collect(),
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
        if !parts
            .iter()
            .all(|(file, _)| prepared.files.contains_key(&file.id))
        {
            let _ = client
                .cancel(HTTPS, &h.host, h.port, &prepared.session_id)
                .await;
            return Err(TransferError::Declined);
        }
        for (file, bytes) in parts {
            let upload = client
                .upload(
                    HTTPS,
                    &h.host,
                    h.port,
                    None,
                    &prepared.session_id,
                    &file.id,
                    &prepared.files[&file.id],
                    bytes.into(),
                    self.stop.clone(),
                )
                .await;
            if let Err(error) = upload {
                let _ = client
                    .cancel(HTTPS, &h.host, h.port, &prepared.session_id)
                    .await;
                return Err(error.into());
            }
        }
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
