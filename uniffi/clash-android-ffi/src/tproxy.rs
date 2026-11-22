//! Native entry point for the TPROXY launcher. App-only APIs that use the
//! Android platform certificate verifier continue to run in the controller JVM.
use std::{
    io::{self, Read},
    net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr},
    os::fd::AsRawFd,
    path::PathBuf,
    time::Duration,
};

use eyre::Result;
use serde::Deserialize;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpSocket, TcpStream, UnixListener},
    task::JoinSet,
    time::timeout,
};

use crate::{ProfileOverride, start_android_core};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct Session {
    path: String,
    work_dir: String,
    control: PathBuf,
    ipv6: bool,
    fake_ip: bool,
    tproxy_port: u16,
    tcp_dns_port: Option<u16>,
}

// TPROXY preserves the original destination address. A regular DNS TCP
// listener cannot accept that SYN, so bridge the stream into Clash's own
// DNS server. It handles DNS framing, repeated queries and large replies.
fn transparent_tcp_dns(addr: SocketAddr) -> io::Result<TcpListener> {
    let (socket, level, option) = if addr.is_ipv4() {
        (TcpSocket::new_v4()?, libc::SOL_IP, libc::IP_TRANSPARENT)
    } else {
        (TcpSocket::new_v6()?, libc::SOL_IPV6, libc::IPV6_TRANSPARENT)
    };
    let enabled: libc::c_int = 1;
    let result = unsafe {
        libc::setsockopt(
            socket.as_raw_fd(),
            level,
            option,
            (&enabled as *const libc::c_int).cast(),
            size_of_val(&enabled) as _,
        )
    };
    if result == -1 {
        return Err(io::Error::last_os_error());
    }
    socket.set_reuseaddr(true)?;
    socket.bind(addr)?;
    socket.listen(128)
}

async fn forward_dns(listener: TcpListener, backend: SocketAddr) -> io::Result<()> {
    let mut requests = JoinSet::new();
    loop {
        tokio::select! {
            result = listener.accept(), if requests.len() < 256 => {
                let (mut client, _) = result?;
                requests.spawn(async move {
                    let mut server = TcpStream::connect(backend).await?;
                    tokio::io::copy_bidirectional(&mut client, &mut server).await
                });
            }
            Some(_) = requests.join_next(), if !requests.is_empty() => {}
        }
    }
}

// Match the address representation in Android /proc/net/tcp{,6}. The
// manager checks that the backend socket inode belongs to this process.
fn proc_endpoint(addr: SocketAddr) -> String {
    let ip = match addr.ip() {
        IpAddr::V4(ip) => format!("{:08X}", u32::from_ne_bytes(ip.octets())),
        IpAddr::V6(ip) => ip
            .octets()
            .chunks_exact(4)
            .map(|word| format!("{:08X}", u32::from_ne_bytes(word.try_into().unwrap())))
            .collect(),
    };
    format!("{ip}:{:04X}", addr.port())
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
        let (core, tcp_dns) = start_android_core(
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
            Some(config.tcp_dns_port.is_some()),
        )
        .await?;
        let mut dns_listeners = JoinSet::new();
        let mut backend_endpoint = String::new();
        if let (Some(port), Some(mut backend)) = (config.tcp_dns_port, tcp_dns) {
            eyre::ensure!(
                backend.port() != 0
                    && backend.port() != port
                    && backend.port() != config.tproxy_port,
                "Profile TCP DNS port conflicts with a managed TPROXY listener or is zero"
            );
            backend_endpoint = format!(" {}", proc_endpoint(backend));
            if backend.ip().is_unspecified() {
                backend.set_ip(if backend.is_ipv4() {
                    Ipv4Addr::LOCALHOST.into()
                } else {
                    Ipv6Addr::LOCALHOST.into()
                });
            }
            for ip in [
                IpAddr::V4(Ipv4Addr::LOCALHOST),
                IpAddr::V6(Ipv6Addr::LOCALHOST),
            ] {
                if ip.is_ipv4() || config.ipv6 {
                    let listener = transparent_tcp_dns(SocketAddr::new(ip, port))?;
                    dns_listeners.spawn(forward_dns(listener, backend));
                }
            }
        }
        let mut active = false;
        loop {
            let (mut peer, _) = tokio::select! {
                _ = core.await_stopped() => eyre::bail!("Core exited"),
                Some(result) = dns_listeners.join_next(), if !dns_listeners.is_empty() => {
                    result??;
                    eyre::bail!("TCP DNS listener stopped");
                }
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
            let reply = format!("{state} {}{backend_endpoint}\n", core.mixed_port());
            let _ = peer.write_all(reply.as_bytes()).await;
        }
        Ok(())
    }
    .await;
    drop(socket);
    std::fs::remove_file(config.control)?;
    result
}
