#!/usr/bin/env bash
set -euo pipefail
variant=${1:?build variant}
readelf=( "${ANDROID_NDK_HOME:?}"/toolchains/llvm/prebuilt/*/bin/llvm-readelf* )
apks=( app/build/outputs/apk/"$variant"/*-universal-"$variant"*.apk )
[[ ${#apks[@]} == 1 && -f "${apks[0]}" ]] || { echo "Expected one universal APK" >&2; exit 1; }
work=$(mktemp -d)
trap 'rm -f "$work/packed.so"; rmdir "$work"' EXIT
mkdir -p build
manifest="build/native-symbols-$variant.txt"
git rev-parse HEAD > "$manifest"
build_id() { "${readelf[0]}" -n "$1" | awk '/Build ID:/ {print $3}'; }
for abi in arm64-v8a armeabi-v7a x86 x86_64; do
  lib=libclash_android_ffi.so
  symbols="core/build/rustJniLibs/android/$abi/$lib"
  unzip -p "${apks[0]}" "lib/$abi/$lib" > "$work/packed.so"
  id=$(build_id "$symbols")
  [[ -n "$id" && "$id" == "$(build_id "$work/packed.so")" ]] || { echo "Missing or mismatched build ID for $abi" >&2; exit 1; }
  sections=$("${readelf[0]}" -SW "$symbols")
  [[ "$sections" == *".debug_line "* ]] || { echo "Missing line tables for $abi" >&2; exit 1; }
  packed_sections=$("${readelf[0]}" -SW "$work/packed.so")
  [[ "$packed_sections" != *".debug_line "* ]] || { echo "Unstripped library in APK for $abi" >&2; exit 1; }
  generated=( app/build/crashlytics/"$variant"/nativeSymbols/*-"$id".sym )
  [[ ${#generated[@]} == 1 && -s "${generated[0]}" ]] || { echo "Missing or ambiguous Crashlytics symbol file for $abi ($id)" >&2; exit 1; }
  echo "$abi $lib $id" | tee -a "$manifest"
done
test -s "app/build/outputs/mapping/$variant/mapping.txt"
