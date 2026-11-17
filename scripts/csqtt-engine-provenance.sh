#!/usr/bin/env bash
# Write app/src/main/assets/csqtt-engine.json with the exact provenance of the
# engine binaries that will be packaged into the APK.
#
# Usage:
#   bash scripts/csqtt-engine-provenance.sh [abi ...]
#
# Defaults to CSQTT_TARGET_ABIS (comma/space separated) or every ABI listed in
# the lock file. Every requested ABI must already have libcsqtt.so installed.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="$ROOT_DIR/scripts/csqtt-engine.lock.json"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"
OUTPUT="$ROOT_DIR/app/src/main/assets/csqtt-engine.json"

die() { printf '[csqtt-provenance] error: %s\n' "$*" >&2; exit 1; }
log() { printf '[csqtt-provenance] %s\n' "$*"; }

command -v jq >/dev/null 2>&1 || die "jq is required"
[[ -f "$LOCK_FILE" ]] || die "lock file not found: $LOCK_FILE"

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 -r "$1" | awk '{print $1}'
  else
    die "no SHA-256 tool found (sha256sum, shasum or openssl)"
  fi
}

file_size() {
  wc -c < "$1" | tr -d ' '
}

requested_abis=("$@")
if [[ "${#requested_abis[@]}" -eq 0 ]]; then
  if [[ -n "${CSQTT_TARGET_ABIS:-${TARGET_ABIS:-}}" ]]; then
    raw_abis="${CSQTT_TARGET_ABIS:-${TARGET_ABIS:-}}"
    read -r -a requested_abis <<< "${raw_abis//,/ }"
  else
    while IFS= read -r abi; do
      [[ -n "$abi" ]] && requested_abis+=("$abi")
    done < <(jq -r '.abis[]' "$LOCK_FILE")
  fi
fi
[[ "${#requested_abis[@]}" -gt 0 ]] || die "no ABIs requested"

version="$(jq -r '.version' "$LOCK_FILE")"
tag="$(jq -r '.tag // empty' "$LOCK_FILE")"
commit="$(jq -r '.commit' "$LOCK_FILE")"
source_url="$(jq -r '.sourceUrl' "$LOCK_FILE")"
archive_url="$(jq -r '.sourceArchive // empty' "$LOCK_FILE")"
archive_sha="$(jq -r '.sourceArchiveSha256 // empty' "$LOCK_FILE")"
license_id="$(jq -r '.license' "$LOCK_FILE")"
license_file="$(jq -r '.licenseFile' "$LOCK_FILE")"
rust_version="$(jq -r '.rust' "$LOCK_FILE")"
cargo_ndk_version="$(jq -r '.cargoNdk // empty' "$LOCK_FILE")"
ndk_version="$(jq -r '.ndk' "$LOCK_FILE")"
api_level="$(jq -r '.api' "$LOCK_FILE")"
binary_name="$(jq -r '.binaryName // "libcsqtt.so"' "$LOCK_FILE")"

[[ -f "$ROOT_DIR/$license_file" ]] || die "license file is missing: $license_file"
license_sha="$(sha256_file "$ROOT_DIR/$license_file")"

abis_json='{}'
built_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

for abi in "${requested_abis[@]}"; do
  case "$abi" in
    arm64-v8a|armeabi-v7a|x86_64) ;;
    *) die "unsupported ABI: $abi" ;;
  esac

  binary="$JNI_DIR/$abi/$binary_name"
  [[ -f "$binary" ]] || die "missing binary: $binary (run scripts/build-csqtt-engine.sh)"

  sha="$(sha256_file "$binary")"
  size="$(file_size "$binary")"
  log "$abi: $size bytes, sha256 $sha"

  abis_json="$(printf '%s' "$abis_json" | jq \
    --arg abi "$abi" \
    --arg file "$binary_name" \
    --arg sha "$sha" \
    --argjson size "$size" \
    '. + {($abi): {file: $file, size: $size, sha256: $sha}}')"
done

mkdir -p "$(dirname "$OUTPUT")"
tmp="$(mktemp "${OUTPUT}.XXXXXX")"
trap 'rm -f "$tmp"' EXIT

jq -n \
  --arg schema "qwdtt.csqtt-engine-provenance.v1" \
  --arg name "CSQTT" \
  --arg version "$version" \
  --arg tag "$tag" \
  --arg commit "$commit" \
  --arg sourceUrl "$source_url" \
  --arg sourceArchive "$archive_url" \
  --arg sourceArchiveSha256 "$archive_sha" \
  --arg license "$license_id" \
  --arg licenseFile "$license_file" \
  --arg licenseSha256 "$license_sha" \
  --arg author "amurcanov" \
  --arg rust "$rust_version" \
  --arg cargoNdk "$cargo_ndk_version" \
  --arg ndk "$ndk_version" \
  --argjson api "$api_level" \
  --arg builtAtUtc "$built_at" \
  --argjson abis "$abis_json" \
  '{
    schema: $schema,
    name: $name,
    version: $version,
    tag: $tag,
    commit: $commit,
    sourceUrl: $sourceUrl,
    sourceArchive: $sourceArchive,
    sourceArchiveSha256: $sourceArchiveSha256,
    author: $author,
    license: $license,
    licenseFile: $licenseFile,
    licenseSha256: $licenseSha256,
    rust: $rust,
    cargoNdk: $cargoNdk,
    ndk: $ndk,
    api: $api,
    builtAtUtc: $builtAtUtc,
    abis: $abis
  }' > "$tmp"

mv -f "$tmp" "$OUTPUT"
trap - EXIT
log "wrote $OUTPUT"
