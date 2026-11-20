[positional-arguments]
generate-bindings *cargo_args:
  cd uniffi && cargo ndk -t arm64-v8a build "$@" -p clash-android-ffi
  cd uniffi && cargo run "$@" -p uniffi-bindgen generate \
    --library target/aarch64-linux-android/debug/libclash_android_ffi.so \
    --language kotlin \
    --out-dir ../core/src/main/java
  cd localsend && cargo ndk -t arm64-v8a build "$@"
  cd localsend && cargo run --manifest-path ../uniffi/Cargo.toml "$@" -p uniffi-bindgen generate \
    --library target/aarch64-linux-android/debug/libprofile_transfer.so \
    --language kotlin \
    --out-dir ../core/src/main/java

build:
  ./gradlew assembleDebug
