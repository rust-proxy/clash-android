use crate::{
    EyreError,
    android_config::{AndroidConfig, ProfileOverride, build_config},
};
use clash_lib::{config::def::Config as ConfigDef, shutdown as clash_shutdown, start};
use std::{net::SocketAddr, path::PathBuf, sync::Arc};
use tokio_util::sync::CancellationToken;
use tracing::{error, info};

#[derive(uniffi::Object)]
pub struct ClashInstance {
    mixed_port: u16,
    cancel_token: CancellationToken,
    stopped: CancellationToken,
}

#[uniffi::export(async_runtime = "tokio")]
impl ClashInstance {
    /// The configured mixed HTTP/SOCKS port, or zero when none is configured.
    pub fn mixed_port(&self) -> u16 {
        self.mixed_port
    }

    pub async fn await_stopped(&self) {
        self.stopped.cancelled().await;
    }

    /// Shut down clash-rs and cancel the background task
    pub fn shutdown(&self) {
        self.cancel_token.cancel();
        clash_shutdown();
        info!("clash-rs shutdown");
    }
}

#[uniffi::export(async_runtime = "tokio")]
async fn run_clash(
    config_path: String,
    work_dir: String,
    over: ProfileOverride,
) -> Result<Arc<ClashInstance>, EyreError> {
    let (core, _) = start_android_core(config_path, work_dir, over, None).await?;
    Ok(core)
}

pub(crate) async fn start_android_core(
    config_path: String,
    work_dir: String,
    over: ProfileOverride,
    tproxy_dns: Option<bool>,
) -> Result<(Arc<ClashInstance>, Option<SocketAddr>), EyreError> {
    std::env::set_current_dir(&work_dir)?;
    let AndroidConfig {
        core: config,
        mixed_port,
        tcp_dns,
    } = build_config(
        ConfigDef::try_from(PathBuf::from(&config_path))?,
        &work_dir,
        &over,
        tproxy_dns,
    )?;
    info!(config_path, tun_fd = ?over.tun_fd, "Starting Android core");

    let cancel_token = CancellationToken::new();
    let token = cancel_token.clone();
    let stopped = CancellationToken::new();
    let completion = stopped.clone().drop_guard();
    tokio::spawn(async move {
        let _completion = completion;
        let (log_tx, _) = tokio::sync::broadcast::channel(100);
        info!("Starting clash-rs");
        // Android owns config loading and applies TUN/DNS overrides above.
        // Do not let the controller reload the unmodified profile implicitly.
        let start_result = start(config, work_dir, None, log_tx, token.child_token());
        tokio::select! {
            result = start_result => {
                if let Err(err) = result {
                    error!("clash-rs start error: {:#}", eyre::eyre!(err));
                }
            }
            _ = token.cancelled() => {
                info!("clash-rs cancelled");
            }
        }
        info!("Quitting clash-rs");
    });

    Ok((
        Arc::new(ClashInstance {
            mixed_port,
            cancel_token,
            stopped,
        }),
        tcp_dns,
    ))
}
