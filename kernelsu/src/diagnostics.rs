use serde::Serialize;
use std::{
    collections::{HashSet, VecDeque},
    error::Error,
    fs, io,
    io::{BufRead, BufReader},
    path::Path,
    process::{Command, Stdio},
};

#[derive(Serialize)]
struct Socket {
    uid: u32,
    inode: String,
    state: String,
}

#[derive(Serialize)]
struct Core {
    pid: u32,
    uid: u32,
    sockets: Vec<Socket>,
    socket_error: Option<String>,
    timeouts: Vec<String>,
    log_error: Option<String>,
}

#[derive(Serialize)]
struct Snapshot {
    processes: Vec<Core>,
    route4: Vec<String>,
    route6: Vec<String>,
}

fn routes(family: &str) -> Vec<String> {
    let mut rows = Vec::new();
    for (args, label) in [
        (&["rule", "show"][..], "策略规则"),
        (&["route", "show", "table", "17893"][..], "路由表"),
    ] {
        match Command::new("/system/bin/ip")
            .arg(family)
            .args(args)
            .output()
        {
            Ok(out) if out.status.success() => {
                for line in String::from_utf8_lossy(&out.stdout).lines() {
                    let line = line.trim();
                    if !line.is_empty() && (args[0] != "rule" || line.starts_with("9055:")) {
                        rows.push(line.to_owned());
                    }
                }
            }
            _ => rows.push(format!("读取{label}失败")),
        }
    }
    rows
}

fn owned_sockets(proc_dir: &Path) -> io::Result<Vec<Socket>> {
    let mut owned = HashSet::new();
    for fd in fs::read_dir(proc_dir.join("fd"))? {
        let target = match fs::read_link(fd?.path()) {
            Ok(target) => target,
            Err(e) if e.kind() == io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e),
        };
        if let Some(inode) = target
            .to_str()
            .and_then(|s| s.strip_prefix("socket:["))
            .and_then(|s| s.strip_suffix(']'))
        {
            owned.insert(inode.to_owned());
        }
    }
    let mut rows = Vec::new();
    for table in ["tcp", "tcp6"] {
        let data = match fs::read_to_string(proc_dir.join("net").join(table)) {
            Ok(data) => data,
            Err(e) if table == "tcp6" && e.kind() == io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e),
        };
        for line in data.lines().skip(1) {
            let fields: Vec<_> = line.split_ascii_whitespace().take(10).collect();
            if let [_, _, remote, state, _, _, _, uid, _, inode] = fields.as_slice()
                && remote.ends_with(":0355")
                && owned.contains(*inode)
            {
                rows.push(Socket {
                    uid: uid.parse().map_err(io::Error::other)?,
                    inode: (*inode).into(),
                    state: (*state).into(),
                });
            }
        }
    }
    Ok(rows)
}

fn recent_timeouts(pid: u32) -> io::Result<Vec<String>> {
    let mut child = Command::new("/system/bin/logcat")
        .args([
            "-b",
            "main",
            "-d",
            &format!("--pid={pid}"),
            "-v",
            "time",
            "clash-rs:V",
            "*:S",
        ])
        .stdout(Stdio::piped())
        .spawn()?;
    let mut recent = VecDeque::with_capacity(20);
    let output = child.stdout.take().expect("piped stdout");
    let result = BufReader::new(output)
        .split(b'\n')
        .try_for_each(|line| -> io::Result<()> {
            let line = line?;
            let line = String::from_utf8_lossy(&line);
            if line.contains("DNS query timeout") {
                if recent.len() == 20 {
                    recent.pop_front();
                }
                recent.push_back(line.into_owned());
            }
            Ok(())
        });
    let status = child.wait()?;
    result?;
    if !status.success() {
        return Err(io::Error::other(format!("logcat: {status}")));
    }
    Ok(recent.into())
}

fn processes() -> io::Result<Vec<Core>> {
    let mut cores = Vec::new();
    for entry in fs::read_dir("/proc")? {
        let dir = entry?;
        let Some(pid) = dir.file_name().to_str().and_then(|n| n.parse::<u32>().ok()) else {
            continue;
        };
        let path = dir.path();
        if pid == std::process::id()
            || fs::read_to_string(path.join("comm"))
                .as_deref()
                .unwrap_or("")
                .trim_end()
                != "clash-tproxy"
        {
            continue;
        }
        let exe = match fs::read_link(path.join("exe")) {
            Ok(exe) => exe,
            Err(e) if e.kind() == io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e),
        };
        let Some(exe) = exe.to_str() else { continue };
        let exe = Path::new(exe.strip_suffix(" (deleted)").unwrap_or(exe));
        let Some(relative) = ["modules", "modules_update"].iter().find_map(|dir| {
            exe.strip_prefix(format!("/data/adb/{dir}/clash_android_tproxy/bin"))
                .ok()
        }) else {
            continue;
        };
        if relative.components().count() != 2
            || relative.file_name().is_none_or(|n| n != "clash-tproxy")
        {
            continue;
        }
        let status = match fs::read_to_string(path.join("status")) {
            Ok(status) => status,
            Err(e) if e.kind() == io::ErrorKind::NotFound => continue,
            Err(e) => return Err(e),
        };
        let uid = status
            .lines()
            .find_map(|l| l.strip_prefix("Uid:"))
            .and_then(|l| l.split_whitespace().nth(1))
            .and_then(|n| n.parse().ok())
            .ok_or_else(|| io::Error::other("无法读取内核有效 UID"))?;
        let sockets = owned_sockets(&path);
        let timeouts = recent_timeouts(pid);
        cores.push(Core {
            pid,
            uid,
            socket_error: sockets.as_ref().err().map(ToString::to_string),
            sockets: sockets.unwrap_or_default(),
            log_error: timeouts.as_ref().err().map(ToString::to_string),
            timeouts: timeouts.unwrap_or_default(),
        });
    }
    Ok(cores)
}

pub fn report() -> Result<(), Box<dyn Error>> {
    if unsafe { libc::geteuid() } != 0 {
        return Err("KernelSU root required".into());
    }
    let snapshot = Snapshot {
        processes: processes()?,
        route4: routes("-4"),
        route6: routes("-6"),
    };
    serde_json::to_writer(io::stdout().lock(), &snapshot)?;
    Ok(())
}
