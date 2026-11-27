[positional-arguments]
generate-bindings *cargo_args:
  cd uniffi && eval "$(cargo ndk-env -t arm64-v8a --platform 23)" && \
    cargo ndk -t arm64-v8a --platform 23 build "$@" -p clash-android-ffi \
      --config "target.aarch64-linux-android.rustflags=['-Lnative=$CARGO_NDK_SYSROOT_LIBS_PATH/23','-Lnative=$CARGO_NDK_SYSROOT_LIBS_PATH']"
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
