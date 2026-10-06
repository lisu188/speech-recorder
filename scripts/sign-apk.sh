#!/usr/bin/env bash
set -euo pipefail
umask 077

variant="${1:?Usage: sign-apk.sh release|standalone input.apk output.apk}"
input="${2:?Input APK is required}"
output="${3:?Output APK is required}"
case "$variant" in
  release) expected="c311a44e405ccfab2b822d5295c45e4dbbc6972516c3695dadb146b6149ec2b6" ;;
  standalone) expected="afe1498136f756801c385653c7f34f1597a423da437398895f6a9d6c710d03a5" ;;
  *) echo "Unknown variant: $variant" >&2; exit 2 ;;
esac
: "${SIGNING_KEYSTORE_PATH:?Set SIGNING_KEYSTORE_PATH to the existing keystore}"
: "${SIGNING_KEY_ALIAS:?Set SIGNING_KEY_ALIAS}"
: "${SIGNING_KEYSTORE_PASSWORD:?Set SIGNING_KEYSTORE_PASSWORD}"
: "${SIGNING_KEY_PASSWORD:?Set SIGNING_KEY_PASSWORD}"
[[ -f "$input" && -f "$SIGNING_KEYSTORE_PATH" ]] || { echo "Input APK or keystore missing" >&2; exit 2; }
[[ "$(realpath -m "$input")" != "$(realpath -m "$output")" ]] || { echo "Input and output must differ" >&2; exit 2; }
[[ ! -e "$output" && ! -L "$output" && ! -e "$output.sha256" && ! -L "$output.sha256" ]] || { echo "Output or checksum already exists; choose a new path" >&2; exit 2; }
if [[ -n "${APKSIGNER_JAR:-}" ]]; then
  signer=(java -jar "$APKSIGNER_JAR")
else
  signer=("${APKSIGNER:-${ANDROID_HOME:?Set ANDROID_HOME, APKSIGNER or APKSIGNER_JAR}/build-tools/37.0.0/apksigner}")
fi
work="$(mktemp -d "$(dirname "$output")/.speech-recorder-sign.XXXXXX")"
published=0
complete=0
cleanup() {
  if [[ "$complete" -eq 0 && "$published" -eq 1 && "$output" -ef "$work/signed.apk" ]]; then
    rm -f -- "$output"
  fi
  rm -rf -- "$work"
}
trap cleanup EXIT
"${signer[@]}" sign \
  --ks "$SIGNING_KEYSTORE_PATH" \
  --ks-key-alias "$SIGNING_KEY_ALIAS" \
  --ks-pass env:SIGNING_KEYSTORE_PASSWORD \
  --key-pass env:SIGNING_KEY_PASSWORD \
  --out "$work/signed.apk" "$input"
verification="$("${signer[@]}" verify --verbose --print-certs "$work/signed.apk")"
actual="$(printf '%s\n' "$verification" | sed -n 's/^Signer #[0-9][0-9]* certificate SHA-256 digest: //p' | tr '[:upper:]' '[:lower:]')"
[[ "$actual" == "$expected" ]] || {
  echo "Refusing APK signed with the wrong certificate: $actual" >&2
  echo "Required $variant certificate: $expected" >&2
  exit 1
}
printf '%s\n' "$verification"
[[ -s "$work/signed.apk" ]] || { echo "Signer produced an empty APK" >&2; exit 1; }
checksum="$(sha256sum "$work/signed.apk")"
printf '%s  %s\n' "${checksum%% *}" "$(basename "$output")" > "$work/signed.apk.sha256"
ln -T -- "$work/signed.apk" "$output"
published=1
ln -T -- "$work/signed.apk.sha256" "$output.sha256"
complete=1
echo "Verified $variant APK: $output"
