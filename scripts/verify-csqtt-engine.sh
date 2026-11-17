#!/usr/bin/env bash
# Verify CSQTT engine binaries and their provenance asset.
#
# Checks:
#   - file exists for every requested ABI;
#   - ELF class/machine matches the ABI;
#   - no dependency on libc++_shared.so;
#   - size is within the lock-file budget;
#   - 16 KB page alignment (64-bit ABIs) for Android 15;
#   - assets/csqtt-engine.json matches version, commit and SHA-256.
#
# Environment:
#   CSQTT_TARGET_ABIS        ABI list to verify (default: all present)
#   CSQTT_REQUIRE_16K_PAGES  1 (default) = fail if a 64-bit binary is not 16 KB aligned
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="$ROOT_DIR/scripts/csqtt-engine.lock.json"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"
ASSET="$ROOT_DIR/app/src/main/assets/csqtt-engine.json"

ERRORS=0
fail() { printf '[csqtt-verify] ERROR: %s\n' "$*" >&2; ERRORS=$((ERRORS + 1)); }
log() { printf '[csqtt-verify] %s\n' "$*"; }

command -v readelf >/dev/null 2>&1 || { echo "[csqtt-verify] ERROR: readelf is required (binutils)" >&2; exit 1; }
command -v jq >/dev/null 2>&1 || { echo "[csqtt-verify] ERROR: jq is required" >&2; exit 1; }
[[ -f "$LOCK_FILE" ]] || { echo "[csqtt-verify] ERROR: lock file not found: $LOCK_FILE" >&2; exit 1; }

version="$(jq -r '.version' "$LOCK_FILE")"
tag="$(jq -r '.tag // empty' "$LOCK_FILE")"
commit="$(jq -r '.commit' "$LOCK_FILE")"
binary_name="$(jq -r '.binaryName // "libcsqtt.so"' "$LOCK_FILE")"
max_size="$(jq -r '.maxSizeBytes // 12582912' "$LOCK_FILE")"
license_file="$(jq -r '.licenseFile // empty' "$LOCK_FILE")"

declare_requested=0
requested_abis=()
if [[ -n "${CSQTT_TARGET_ABIS:-${TARGET_ABIS:-}}" ]]; then
  raw_abis="${CSQTT_TARGET_ABIS:-${TARGET_ABIS:-}}"
  read -r -a requested_abis <<< "${raw_abis//,/ }"
  declare_requested=1
else
  while IFS= read -r abi; do
    [[ -n "$abi" ]] || continue
    if [[ -f "$JNI_DIR/$abi/$binary_name" ]]; then
      requested_abis+=("$abi")
    fi
  done < <(jq -r '.abis[]' "$LOCK_FILE")
fi
[[ "${#requested_abis[@]}" -gt 0 ]] || { echo "[csqtt-verify] ERROR: no engine binaries found in $JNI_DIR" >&2; exit 1; }

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  elif command -v openssl >/dev/null 2>&1; then
    openssl dgst -sha256 -r "$1" | awk '{print $1}'
  else
    return 1
  fi
}

file_size() { wc -c < "$1" | tr -d ' '; }

expected_machine() {
  case "$1" in
    arm64-v8a) echo "AArch64" ;;
    armeabi-v7a) echo "ARM" ;;
    x86_64) echo "Advanced Micro Devices X86-64" ;;
    *) echo "" ;;
  esac
}

expected_class() {
  case "$1" in
    arm64-v8a|x86_64) echo "ELF64" ;;
    armeabi-v7a) echo "ELF32" ;;
    *) echo "" ;;
  esac
}

for abi in "${requested_abis[@]}"; do
  binary="$JNI_DIR/$abi/$binary_name"

  if [[ ! -f "$binary" ]]; then
    if [[ "$declare_requested" == 1 ]]; then
      fail "$abi: missing $binary (requested ABI)"
    fi
    continue
  fi

  size="$(file_size "$binary")"
  log "$abi: $size bytes"
  if [[ "$size" -gt "$max_size" ]]; then
    fail "$abi: size $size exceeds budget $max_size"
  fi

  class="$(readelf -h "$binary" | awk -F: '/Class:/{gsub(/^[ \t]+/, "", $2); print $2; exit}')"
  machine="$(readelf -h "$binary" | awk -F: '/Machine:/{gsub(/^[ \t]+/, "", $2); print $2; exit}')"
  elf_type="$(readelf -h "$binary" | awk -F: '/Type:/{gsub(/^[ \t]+/, "", $2); print $2; exit}')"

  want_class="$(expected_class "$abi")"
  want_machine="$(expected_machine "$abi")"
  [[ "$class" == "$want_class" ]] || fail "$abi: ELF class '$class', expected '$want_class'"
  if ! printf '%s' "$machine" | grep -qiF "$want_machine"; then
    fail "$abi: ELF machine '$machine', expected '$want_machine'"
  fi
  printf '%s' "$elf_type" | grep -Eq 'DYN|EXEC' || fail "$abi: unexpected ELF type '$elf_type' (expected DYN/EXEC)"

  if readelf -dW "$binary" 2>/dev/null | grep -qi 'libc++_shared\.so'; then
    fail "$abi: depends on libc++_shared.so; use the static C++ runtime"
  fi

  load_aligns="$(readelf -lW "$binary" 2>/dev/null | awk '$1 == "LOAD" {print $NF}')"
  [[ -n "$load_aligns" ]] || fail "$abi: no LOAD segments found"
  min_align=0
  while IFS= read -r align; do
    [[ -n "$align" ]] || continue
    value="$(printf '%d' "$align" 2>/dev/null || echo 0)"
    if [[ "$value" -gt "$min_align" ]]; then
      min_align="$value"
    fi
  done <<< "$load_aligns"

  if [[ "$min_align" -lt 16384 ]]; then
    if [[ "$abi" == "arm64-v8a" || "$abi" == "x86_64" ]] && [[ "${CSQTT_REQUIRE_16K_PAGES:-1}" == "1" ]]; then
      fail "$abi: LOAD alignment $((min_align)) < 16384 (Android 15 16 KB page size)"
    else
      log "$abi: note: LOAD alignment $((min_align)) < 16384"
    fi
  else
    log "$abi: LOAD alignment $((min_align))"
  fi
done

if [[ ! -f "$ASSET" ]]; then
  fail "provenance asset is missing: $ASSET (run scripts/csqtt-engine-provenance.sh)"
else
  if ! jq -e . "$ASSET" >/dev/null 2>&1; then
    fail "provenance asset is not valid JSON: $ASSET"
  else
    asset_version="$(jq -r '.version // empty' "$ASSET")"
    asset_commit="$(jq -r '.commit // empty' "$ASSET")"
    asset_tag="$(jq -r '.tag // empty' "$ASSET")"
    [[ "$asset_version" == "$version" ]] || fail "provenance version '$asset_version' != lock '$version'"
    [[ "$asset_commit" == "$commit" ]] || fail "provenance commit '$asset_commit' != lock '$commit'"
    [[ -z "$tag" || "$asset_tag" == "$tag" ]] || fail "provenance tag '$asset_tag' != lock '$tag'"
    [[ -n "$(jq -r '.builtAtUtc // empty' "$ASSET")" ]] || fail "provenance builtAtUtc is empty"
    [[ -n "$(jq -r '.rust // empty' "$ASSET")" ]] || fail "provenance rust version is empty"
    [[ -n "$(jq -r '.ndk // empty' "$ASSET")" ]] || fail "provenance ndk version is empty"

    for abi in "${requested_abis[@]}"; do
      binary="$JNI_DIR/$abi/$binary_name"
      [[ -f "$binary" ]] || continue

      recorded_sha="$(jq -r --arg abi "$abi" '.abis[$abi].sha256 // empty' "$ASSET")"
      recorded_size="$(jq -r --arg abi "$abi" '.abis[$abi].size // empty' "$ASSET")"
      actual_sha="$(sha256_file "$binary")"
      actual_size="$(file_size "$binary")"

      [[ -n "$recorded_sha" ]] || fail "$abi: no sha256 in provenance asset"
      [[ "$recorded_sha" == "$actual_sha" ]] || fail "$abi: sha256 mismatch (asset $recorded_sha, actual $actual_sha)"
      [[ "$recorded_size" == "$actual_size" ]] || fail "$abi: size mismatch (asset $recorded_size, actual $actual_size)"
    done
  fi
fi

if [[ -n "$license_file" && ! -f "$ROOT_DIR/$license_file" ]]; then
  fail "license notice is missing: $license_file"
fi

if [[ "$ERRORS" -gt 0 ]]; then
  printf '[csqtt-verify] FAILED with %s error(s)\n' "$ERRORS" >&2
  exit 1
fi

log "OK: ${requested_abis[*]} (CSQTT $version, commit $commit)"
