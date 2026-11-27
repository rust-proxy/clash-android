fn main() {
    if std::env::var("CARGO_CFG_TARGET_OS").as_deref() == Ok("android") {
        println!("cargo::rustc-link-arg-cdylib=-Wl,--build-id=sha1");
        println!(
            "cargo::rustc-link-arg-cdylib=-Wl,-z,max-page-size=16384,-soname,libclash_android_ffi.so"
        );
        println!("cargo::rustc-link-arg-cdylib=-Wl,--pack-dyn-relocs=android");
        // Cronet uses ALooper and Chromium's linker-wrapped Android allocator.
        println!("cargo::rustc-link-arg-cdylib=-landroid");
        for symbol in [
            "aligned_alloc",
            "asprintf",
            "calloc",
            "free",
            "getcwd",
            "malloc",
            "malloc_usable_size",
            "memalign",
            "posix_memalign",
            "pvalloc",
            "realloc",
            "realpath",
            "strdup",
            "strndup",
            "valloc",
            "vasprintf",
        ] {
            println!("cargo::rustc-link-arg-cdylib=-Wl,--wrap={symbol}");
        }
    }
}
