//! Small Android executable. All proxy code lives in the APK's shared library.
#[cfg(target_os = "android")]
fn main() -> Result<(), Box<dyn std::error::Error>> {
    use std::{
        ffi::{CStr, CString},
        io,
        os::unix::ffi::OsStrExt,
    };

    let mut args = std::env::args_os().skip(1);
    let uid: u32 = args
        .next()
        .ok_or("missing UID")?
        .to_str()
        .ok_or("UID")?
        .parse()?;
    let library = CString::new(args.next().ok_or("missing library")?.as_bytes())?;
    if !(10000..20000).contains(&(uid % 100000)) || args.next().is_some() {
        return Err("expected application UID and shared library".into());
    }

    // Android applies its background network policy to sockets owned by an
    // application UID once the UI is gone. Use the unprivileged AID_NOBODY
    // as the effective/socket UID. The real UID identifies the controller for
    // SO_PEERCRED checks in the library; the app GID scopes filesystem access.
    let network_uid = 9999;
    let groups = [3003, uid + 10000]; // INET and Android's per-app cache GID.
    let net_raw = 1u32 << 13;
    let header = [0x2008_0522u32, 0];
    let caps = [net_raw, net_raw, 0, 0, 0, 0];
    // Single-threaded before dlopen. NET_RAW is sufficient for IP_TRANSPARENT.
    unsafe {
        if libc::setsid() < 0
            || libc::prctl(libc::PR_SET_KEEPCAPS, 1, 0, 0, 0) != 0
            || libc::setgroups(groups.len(), groups.as_ptr()) != 0
            || libc::setresgid(uid, uid, uid) != 0
            || libc::setresuid(uid, network_uid, network_uid) != 0
            || libc::syscall(libc::SYS_capset, header.as_ptr(), caps.as_ptr()) != 0
            || libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0
        {
            return Err(io::Error::last_os_error().into());
        }
        libc::umask(0o007);
        libc::prctl(libc::PR_SET_NAME, c"clash-tproxy".as_ptr(), 0, 0, 0);
        let lib = libc::dlopen(library.as_ptr(), libc::RTLD_NOW | libc::RTLD_LOCAL);
        if lib.is_null() {
            return Err(CStr::from_ptr(libc::dlerror())
                .to_string_lossy()
                .into_owned()
                .into());
        }
        let symbol = libc::dlsym(lib, c"clash_android_tproxy_main".as_ptr());
        if symbol.is_null() {
            return Err("missing clash_android_tproxy_main".into());
        }
        let run: unsafe extern "C" fn() -> i32 = std::mem::transmute(symbol);
        std::process::exit(run());
    }
}

#[cfg(not(target_os = "android"))]
fn main() {
    eprintln!("clash-launcher requires Android");
}
