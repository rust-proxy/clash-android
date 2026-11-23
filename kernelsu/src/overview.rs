use serde::{Deserialize, Serialize};
use std::{
    error::Error,
    io::{self, Read, Write},
    os::{fd::AsRawFd, unix::net::UnixStream},
    time::Duration,
};

#[derive(Deserialize, Serialize)]
struct Metadata {
    network: String,
    host: String,
    #[serde(rename = "destinationIP")]
    ip: String,
    #[serde(rename = "destinationPort")]
    port: u16,
}

#[derive(Deserialize, Serialize)]
struct Connection {
    metadata: Metadata,
    upload: u64,
    download: u64,
    rule: String,
    chains: Vec<String>,
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
struct Stats {
    upload_total: u64,
    download_total: u64,
    memory: u64,
    connections: Vec<Connection>,
    #[serde(skip_deserializing)]
    connection_count: usize,
}

#[derive(Serialize)]
struct Core {
    pid: u32,
    uid: u32,
    stats: Option<Stats>,
    error: Option<String>,
}

fn stats(pid: u32) -> Result<Stats, Box<dyn Error>> {
    // start_android_core makes the profile work directory its cwd. A stale
    // socket or an app replacing the path must never supply another PID's data.
    let mut socket = UnixStream::connect(format!("/proc/{pid}/cwd/clash.sock"))?;
    let mut peer = libc::ucred {
        pid: 0,
        uid: 0,
        gid: 0,
    };
    let mut len = size_of_val(&peer) as libc::socklen_t;
    let result = unsafe {
        libc::getsockopt(
            socket.as_raw_fd(),
            libc::SOL_SOCKET,
            libc::SO_PEERCRED,
            (&mut peer as *mut libc::ucred).cast(),
            &mut len,
        )
    };
    if result < 0 {
        return Err(io::Error::last_os_error().into());
    }
    if peer.pid != pid as i32 || peer.uid != 0 {
        return Err("控制套接字与内核进程不匹配".into());
    }
    let timeout = Some(Duration::from_secs(2));
    socket.set_read_timeout(timeout)?;
    socket.set_write_timeout(timeout)?;
    // HTTP/1.0 closes after the JSON response and does not use chunked framing.
    socket
        .write_all(b"GET /connections HTTP/1.0\r\nHost: localhost\r\nConnection: close\r\n\r\n")?;
    const MAX_RESPONSE: u64 = 8 * 1024 * 1024;
    let mut response = Vec::new();
    socket.take(MAX_RESPONSE + 1).read_to_end(&mut response)?;
    if response.len() as u64 > MAX_RESPONSE {
        return Err("连接统计超过大小限制".into());
    }
    let end = response
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or("控制器响应不完整")?;
    let status = std::str::from_utf8(&response[..end])?
        .lines()
        .next()
        .unwrap_or_default();
    if status.split_ascii_whitespace().nth(1) != Some("200") {
        return Err(format!("控制器请求失败 ({status})").into());
    }
    let mut stats: Stats = serde_json::from_slice(&response[end + 4..])?;
    stats.connection_count = stats.connections.len();
    stats
        .connections
        .sort_by_key(|c| std::cmp::Reverse(c.upload.saturating_add(c.download)));
    stats.connections.truncate(5);
    Ok(stats)
}

pub fn report() -> Result<(), Box<dyn Error>> {
    let mut processes = Vec::new();
    for (pid, uid) in crate::diagnostics::core_processes()? {
        let result = stats(pid);
        processes.push(Core {
            pid,
            uid,
            error: result.as_ref().err().map(ToString::to_string),
            stats: result.ok(),
        });
    }
    serde_json::to_writer(io::stdout().lock(), &processes)?;
    Ok(())
}
