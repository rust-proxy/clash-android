fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo::rustc-link-arg-cdylib=-Wl,--build-id=sha1");
        println!("cargo::rustc-link-arg-cdylib=-Wl,--pack-dyn-relocs=android");
    }
}
