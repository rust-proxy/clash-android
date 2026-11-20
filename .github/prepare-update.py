"""Stage the release APKs and their in-app update manifest together."""

import hashlib
import json
import re
import shutil
import sys
from pathlib import Path


def prepare(metadata_file, destination, version, changelog):
    if not re.fullmatch(r"(v[\w.+-]+|dev-[0-9a-f]+)", version, re.ASCII):
        raise ValueError(f"Unsupported release version: {version}")
    metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
    elements = metadata["elements"]
    testing = version.startswith("dev-")
    manifest = {
        "channel": "testing" if testing else "stable",
        "tag": "latest" if testing else version,
        "applicationId": metadata["applicationId"],
        "versionCode": elements[0]["versionCode"],
        "versionName": elements[0]["versionName"],
        "notes": changelog.read_text(encoding="utf-8")[:2400].strip(),
        "assets": {},
    }
    destination.mkdir(parents=True, exist_ok=True)
    for element in elements:
        if any(element[key] != manifest[key] for key in ("versionCode", "versionName")):
            raise ValueError("APK variants have inconsistent versions")
        filters = element["filters"]
        if element["type"] == "UNIVERSAL" and not filters:
            abi = "all"
        elif len(filters) == 1 and filters[0]["filterType"] == "ABI":
            abi = filters[0]["value"]
        else:
            raise ValueError(f"Unexpected APK filters: {filters}")
        if abi in manifest["assets"]:
            raise ValueError(f"Duplicate ABI: {abi}")
        source = metadata_file.parent / element["outputFile"]
        target = destination / f"clash-android-{version}-{abi}.apk"
        shutil.copyfile(source, target)
        with target.open("rb") as apk:
            digest = hashlib.file_digest(apk, "sha256").hexdigest()
        manifest["assets"][abi] = {"file": target.name, "size": target.stat().st_size, "sha256": digest}
    if set(manifest["assets"]) != {"all", "arm64-v8a", "armeabi-v7a", "x86", "x86_64"}:
        raise ValueError("Missing or unexpected release ABI")
    (destination / "update.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    metadata, output, version, notes = sys.argv[1:]
    prepare(Path(metadata), Path(output), version, Path(notes))
