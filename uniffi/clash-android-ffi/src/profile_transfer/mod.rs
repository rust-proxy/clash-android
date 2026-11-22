mod payload;
mod receive;
mod send;

use receive::Pending;

use std::{
    net::Ipv4Addr,
    path::PathBuf,
    sync::{Arc, Mutex, OnceLock},
    time::Duration,
};

use ipnet::Ipv4Net;
use localsend::{
    crypto::cert::{SelfSignedCert, generate_self_signed},
    discovery::{self, DeviceIdentity, DiscoveryConfig, DiscoveryEvent, DiscoveryHandle},
    http::{
        client::ClientError,
        dto_v2::RegisterDtoV2,
        server::{
            ServerConfigV2, ServerHandle, TlsConfig, start_with_port, v2::ServerEventV2,
            web::WebConfig,
        },
        state::ClientInfo,
    },
    model::discovery::{DeviceType, PROTOCOL_VERSION_V2, ProtocolType},
    multicast::MulticastDevice,
    util::interface::InterfaceFilter,
};
use tokio::sync::{mpsc, oneshot};
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
