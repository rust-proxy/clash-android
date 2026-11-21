//! Native entry point for the TPROXY launcher. App-only APIs that use the
//! Android platform certificate verifier continue to run in the controller JVM.
use std::{io::Read, path::PathBuf, time::Duration};

use eyre::Result;
use serde::Deserialize;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::UnixListener,
    time::timeout,
};

use crate::{ProfileOverride, run_clash};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Session {
    path: String,
    work_dir: String,
    control: PathBuf,
    ipv6: bool,
    fake_ip: bool,
    tproxy_port: u16,
}

/// The launcher passes a single JSON document over stdin and calls this once,
/// after dropping credentials. No Kotlin, JVM or UniFFI marshalling is needed.
#[unsafe(no_mangle)]
pub extern "C" fn clash_android_tproxy_main() -> i32 {
    crate::log::init_logger(tracing_subscriber::filter::LevelFilter::INFO);
    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    let run = || -> Result<()> {
        let config: Session = serde_json::from_reader(std::io::stdin().take(1024 * 1024))?;
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .build()?
            .block_on(serve(config))
    };
    match run() {
        Ok(()) => 0,
        Err(err) => {
            tracing::error!("Native TPROXY session ended: {err}");
            1
        }
    }
}

async fn serve(config: Session) -> Result<()> {
    // The one-shot manager serializes launches and checks the old endpoint
    // before starting us. Remove stale socket files only as the application UID.
    match std::fs::remove_file(&config.control) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => return Err(e.into()),
        _ => {}
    }
    let socket = UnixListener::bind(&config.control)?;
    let result = async {
        let core = run_clash(
            config.path,
            config.work_dir,
            ProfileOverride {
                tun_fd: None,
                allow_lan: false,
                mixed_port: Some(7890),
                http_port: None,
                socks_port: None,
                fake_ip: config.fake_ip,
                fake_ip_range: "198.18.0.2/16".into(),
                ipv6: config.ipv6,
                tproxy_port: Some(config.tproxy_port),
            },
        )
        .await?;
        let mut active = false;
        loop {
            let (mut peer, _) = tokio::select! {
                _ = core.await_stopped() => eyre::bail!("Core exited"),
                conn = socket.accept() => conn?,
            };
            let uid = peer.peer_cred()?.uid();
            if uid != 0 && uid != unsafe { libc::getuid() } {
                continue;
            }
            let Ok(Ok(request)) = timeout(Duration::from_secs(1), peer.read_u8()).await else {
                continue;
            };
            match request {
                b'A' if uid == 0 => active = true,
                b'X' if uid == 0 => {
                    core.shutdown();
                    timeout(Duration::from_secs(5), core.await_stopped()).await?;
                    let _ = peer.write_all(b"STOPPED\n").await;
                    break;
                }
                b'?' => {}
                _ => continue,
            }
            let state = if active { "READY" } else { "STARTING" };
            let reply = format!("{state} {}\n", core.mixed_port());
            let _ = peer.write_all(reply.as_bytes()).await;
        }
        Ok(())
    }
    .await;
    drop(socket);
    std::fs::remove_file(config.control)?;
    result
}
