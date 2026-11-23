[positional-arguments]
generate-bindings *cargo_args:
  cd uniffi && cargo ndk -t arm64-v8a build "$@" -p clash-android-ffi
  cd uniffi && cargo run "$@" -p uniffi-bindgen generate \
    --library target/aarch64-linux-android/debug/libclash_android_ffi.so \
    --language kotlin \
    --out-dir ../core/src/main/java
  rm -f core/src/main/java/uniffi/profile_transfer/profile_transfer.kt

build:
  ./gradlew assembleDebug

# Build the standalone KernelSU ZIP for all supported ABIs.
module:
  ./gradlew :app:packageKernelSuModule

# Build an arm64-only debug APK for a physical phone.
phone:
  ./gradlew -PphoneArm64=true :app:assembleDebug
