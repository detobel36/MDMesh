#!/usr/bin/env sh
# Emits manifest.json — the signed fleet-update contract.
# Args: VERSION CHANNEL SERVER_IMG WEB_IMG APK_FILE APK_VERSIONCODE APK_CHECKSUM PROTO_VERSION [WEB_ZIP]
set -eu
python3 - "$@" <<'PY'
import sys, json, hashlib, os
args = sys.argv[1:]
ver, ch, server_img, web_img, apk, apk_vc, apk_ck, proto = args[:8]
web_zip = args[8] if len(args) > 8 else None

sha = hashlib.sha256(open(apk, "rb").read()).hexdigest()
components = {
  "serverImage": server_img, "webImage": web_img,
  "apk": {"file": apk.split("/")[-1], "versionCode": int(apk_vc),
          "sha256": sha, "signatureChecksum": apk_ck},
}
if web_zip and os.path.exists(web_zip):
  web_zip_sha = hashlib.sha256(open(web_zip, "rb").read()).hexdigest()
  components["webZip"] = {
    "file": os.path.basename(web_zip),
    "sha256": web_zip_sha
  }

m = {
  "version": ver, "channel": ch,
  "components": components,
  "compat": {"minAgentProtocol": "1.0", "maxAgentProtocol": proto},
}
open("manifest.json", "w").write(json.dumps(m, indent=2) + "\n")
print("wrote manifest.json")
PY
