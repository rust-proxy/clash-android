[positional-arguments]
[script('sh')]
generate-bindings *cargo_args:
  set -eu
  cd uniffi
  bindings_libs=$(mktemp -d)
  trap 'rm -rf "$bindings_libs"' EXIT
  (
    ndk_env=$(cargo ndk-env -t arm64-v8a --platform 23)
    eval "$ndk_env"
    cargo ndk -t arm64-v8a --platform 23 -o "$bindings_libs" build "$@" -p clash-android-ffi \
      --config "target.aarch64-linux-android.rustflags=['-Lnative=$CARGO_NDK_SYSROOT_LIBS_PATH/23','-Lnative=$CARGO_NDK_SYSROOT_LIBS_PATH']"
  )
  cargo run "$@" -p uniffi-bindgen generate \
    --library "$bindings_libs/arm64-v8a/libclash_android_ffi.so" \
    --language kotlin \
    --out-dir ../core/src/main/java
  rm -f ../core/src/main/java/uniffi/profile_transfer/profile_transfer.kt

build:
  ./gradlew assembleDebug

# Build the standalone KernelSU ZIP for all supported ABIs.
module:
  ./gradlew :app:packageKernelSuModule

# Build an arm64-only debug APK for a physical phone.
phone:
  ./gradlew -PphoneArm64=true :app:assembleDebug
