//! KernelSU starts this process as root and supplies the app UID, library
//! path, and a session on stdin. The APK owns the proxy implementation.
use std::{env, error::Error, ffi::CString, io, os::unix::ffi::OsStrExt};

fn main() -> Result<(), Box<dyn Error>> {
    let mut args = env::args_os().skip(1);
    let app_uid: u32 = args
        .next()
        .and_then(|arg| arg.to_str()?.parse().ok())
        .filter(|uid| (10000..20000).contains(&(uid % 100000)))
        .ok_or("invalid app UID")?;
    let library = CString::new(args.next().ok_or("missing library path")?.as_bytes())?;
    if args.next().is_some() {
        return Err("unexpected launcher argument".into());
    }

    let groups = [3003, app_uid.checked_add(10000).ok_or("invalid cache GID")?];
    // Linux capability ABI v3 uses two (effective, permitted, inheritable)
    // triplets. Only CAP_NET_RAW is needed for IP_TRANSPARENT.
    let header = [0x2008_0522u32, 0];
    let net_raw = 1u32 << 13;
    let caps = [net_raw, net_raw, 0, 0, 0, 0];
    // Single-threaded until the APK's core starts. The primary GID is also
    // checked by the app when it reconnects to the control socket.
    unsafe {
        if libc::getuid() != 0 || libc::geteuid() != 0 {
            return Err("KernelSU root required".into());
        }
        if libc::setsid() < 0
            || libc::setgroups(groups.len(), groups.as_ptr()) != 0
            || libc::setresgid(app_uid, app_uid, app_uid) != 0
            || libc::setresuid(0, 0, 0) != 0
            || libc::syscall(libc::SYS_capset, header.as_ptr(), caps.as_ptr()) != 0
            || libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0
        {
            return Err(io::Error::last_os_error().into());
        }
        libc::umask(0o007);
        let lib = libc::dlopen(library.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
        if lib.is_null() {
            return Err("could not load the installed Clash library".into());
        }
        let symbol = libc::dlsym(lib, c"clash_android_tproxy_main".as_ptr());
        if symbol.is_null() {
            return Err("missing native TPROXY entry point".into());
        }
        let run: unsafe extern "C" fn() -> libc::c_int = std::mem::transmute(symbol);
        std::process::exit(run());
    }
}
