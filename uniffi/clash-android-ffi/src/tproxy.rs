//! Credentials for the app_process exec that hosts the transparent listener.
//! No profile or application-writable code is read before the exec.
#[unsafe(export_name = "Java_rs_clash_android_service_TproxyCore_dropPrivileges")]
pub extern "system" fn drop_privileges(
    _env: *mut jni::sys::JNIEnv,
    _class: jni::sys::jclass,
    uid: jni::sys::jint,
) -> jni::sys::jboolean {
    const CAP_NET_RAW: u32 = 13;
    const INET_GROUP: libc::gid_t = 3003;
    // Linux capability ABI v3 has a two-word header and two 32-bit triples.
    let header = [0x2008_0522u32, 0];
    let bit = 1u32 << CAP_NET_RAW;
    let data = [bit, bit, bit, 0, 0, 0];
    if uid < 10000 {
        return false;
    }
    let uid = uid as libc::uid_t;
    // Only this thread survives exec. Keep NET_RAW for IP_TRANSPARENT, with
    // the application's UID/GID for config, providers and controller files.
    unsafe {
        libc::prctl(libc::PR_SET_KEEPCAPS, 1, 0, 0, 0) == 0
            && libc::setgroups(1, &INET_GROUP) == 0
            && libc::setresgid(uid, uid, uid) == 0
            && libc::setresuid(uid, uid, uid) == 0
            && libc::syscall(libc::SYS_capset, header.as_ptr(), data.as_ptr()) == 0
            && libc::prctl(47, 2, CAP_NET_RAW, 0, 0) == 0 // PR_CAP_AMBIENT_RAISE
            && libc::prctl(libc::PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) == 0
    }
}
