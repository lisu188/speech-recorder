#!/usr/bin/env bash
set -euo pipefail
umask 077

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
signer="$script_dir/sign-apk.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir "$work/bin" "$work/failing-bin" "$work/output"
printf 'unsigned fixture\n' > "$work/input.apk"
printf 'fake keystore fixture\n' > "$work/key.p12"
cat > "$work/bin/apksigner" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
command="${1:?}"
shift
case "$command" in
  sign)
    [[ "${FAKE_SIGN_FAIL:-0}" = 0 ]] || exit 7
    output=""
    while [[ "$#" -gt 0 ]]; do
      case "$1" in
        --out) output="$2"; shift 2 ;;
        --ks-pass) [[ "$2" = env:SIGNING_KEYSTORE_PASSWORD ]]; shift 2 ;;
        --key-pass) [[ "$2" = env:SIGNING_KEY_PASSWORD ]]; shift 2 ;;
        *) shift ;;
      esac
    done
    [[ -n "$output" ]]
    if [[ "${FAKE_EMPTY_APK:-0}" = 1 ]]; then
      : > "$output"
    else
      printf 'signed fixture\n' > "$output"
    fi
    if [[ -n "${FAKE_COLLISION_TARGET:-}" ]]; then
      printf 'existing concurrent output\n' > "$FAKE_COLLISION_TARGET"
    fi
    ;;
  verify)
    [[ "${FAKE_VERIFY_FAIL:-0}" = 0 ]] || exit 8
    printf 'Signer #1 certificate SHA-256 digest: %s\n' "${FAKE_CERT:?}"
    if [[ -n "${FAKE_EXTRA_CERT:-}" ]]; then
      printf 'Signer #2 certificate SHA-256 digest: %s\n' "$FAKE_EXTRA_CERT"
    fi
    ;;
  *) exit 2 ;;
esac
EOF
cat > "$work/failing-bin/sha256sum" <<'EOF'
#!/usr/bin/env bash
exit 9
EOF
chmod +x "$work/bin/apksigner" "$work/failing-bin/sha256sum"
export APKSIGNER="$work/bin/apksigner"
export APKSIGNER_JAR=""
export SIGNING_KEYSTORE_PATH="$work/key.p12"
export SIGNING_KEY_ALIAS="fixture"
export SIGNING_KEYSTORE_PASSWORD="fixture"
export SIGNING_KEY_PASSWORD="fixture"
export FAKE_CERT="c311a44e405ccfab2b822d5295c45e4dbbc6972516c3695dadb146b6149ec2b6"
cases=0

assert_failed() {
  name="$1"
  shift
  if "$@" > "$work/test.log" 2>&1; then
    printf 'FAIL: %s unexpectedly succeeded\n' "$name" >&2
    exit 1
  fi
  cases=$((cases + 1))
}

assert_absent() {
  [[ ! -e "$1" && ! -L "$1" && ! -e "$1.sha256" && ! -L "$1.sha256" ]] || {
    printf 'FAIL: rejected signing published an output\n' >&2
    exit 1
  }
}

for variant in release standalone; do
  if [[ "$variant" = release ]]; then
    certificate="$FAKE_CERT"
  else
    certificate="AFE1498136F756801C385653C7F34F1597A423DA437398895F6A9D6C710D03A5"
  fi
  env FAKE_CERT="$certificate" bash "$signer" "$variant" "$work/input.apk" "$work/output/$variant signed.apk" > "$work/test.log"
  (cd "$work/output" && sha256sum --check "$variant signed.apk.sha256") > "$work/test.log"
  cases=$((cases + 1))
done

output="$work/output/rejected.apk"
assert_failed 'unknown variant' bash "$signer" invalid "$work/input.apk" "$output"
assert_absent "$output"
assert_failed 'missing input' bash "$signer" release "$work/missing.apk" "$output"
assert_absent "$output"
assert_failed 'missing keystore' env SIGNING_KEYSTORE_PATH="$work/missing.p12" bash "$signer" release "$work/input.apk" "$output"
assert_absent "$output"
assert_failed 'missing password' env SIGNING_KEY_PASSWORD= bash "$signer" release "$work/input.apk" "$output"
assert_absent "$output"
assert_failed 'wrong certificate' env FAKE_CERT=incorrect bash "$signer" release "$work/input.apk" "$output"
assert_absent "$output"
assert_failed 'additional signer' env FAKE_EXTRA_CERT="$FAKE_CERT" bash "$signer" release "$work/input.apk" "$output"
assert_absent "$output"
assert_failed 'sign command failure' env FAKE_SIGN_FAIL=1 bash "$signer" release "$work/input.apk" "$output"
assert_absent "$output"
assert_failed 'verify command failure' env FAKE_VERIFY_FAIL=1 bash "$signer" release "$work/input.apk" "$output"
assert_absent "$output"
assert_failed 'empty APK' env FAKE_EMPTY_APK=1 bash "$signer" release "$work/input.apk" "$output"
assert_absent "$output"
assert_failed 'checksum command failure' env PATH="$work/failing-bin:$PATH" bash "$signer" release "$work/input.apk" "$output"
assert_absent "$output"
assert_failed 'input equals output' bash "$signer" release "$work/input.apk" "$work/input.apk"
[[ "$(cat "$work/input.apk")" = 'unsigned fixture' ]]
assert_failed 'existing APK' bash "$signer" release "$work/input.apk" "$work/output/release signed.apk"
(cd "$work/output" && sha256sum --check 'release signed.apk.sha256') > "$work/test.log"
printf 'existing checksum\n' > "$output.sha256"
assert_failed 'existing checksum' bash "$signer" release "$work/input.apk" "$output"
[[ ! -e "$output" && "$(cat "$output.sha256")" = 'existing checksum' ]]
rm "$output.sha256"
ln -s "$work/absent-target" "$output"
assert_failed 'broken output symlink' bash "$signer" release "$work/input.apk" "$output"
[[ -L "$output" ]]
rm "$output"
assert_failed 'concurrent APK' env FAKE_COLLISION_TARGET="$output" bash "$signer" release "$work/input.apk" "$output"
[[ "$(cat "$output")" = 'existing concurrent output' && ! -e "$output.sha256" ]]
rm "$output"
assert_failed 'concurrent checksum' env FAKE_COLLISION_TARGET="$output.sha256" bash "$signer" release "$work/input.apk" "$output"
[[ ! -e "$output" && "$(cat "$output.sha256")" = 'existing concurrent output' ]]
rm "$output.sha256"
if compgen -G "$work/output/.speech-recorder-sign.*" > /dev/null; then
  printf 'FAIL: signing left temporary files\n' >&2
  exit 1
fi
printf 'Signing regression tests passed: %s cases\n' "$cases"
