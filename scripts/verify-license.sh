#!/usr/bin/env bash
# Guardrail: if libcsqtt.so is present, the PolyForm-NC notice and third-party
# attribution must be present too. Also rejects CSQTT Rust sources in the repo.
#
# Usage:
#   bash scripts/verify-license.sh [path/to/app.apk ...]
#
# Exit code 0 when the repository/APK is consistent, 1 otherwise.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"
LICENSE_FILE="$ROOT_DIR/app/src/main/assets/licenses/CSQTT-LICENSE"
NOTICES_FILE="$ROOT_DIR/THIRD_PARTY_NOTICES.md"
PROVENANCE_FILE="$ROOT_DIR/app/src/main/assets/csqtt-engine.json"

ERRORS=0
fail() { printf '[verify-license] ERROR: %s\n' "$*" >&2; ERRORS=$((ERRORS + 1)); }
log() { printf '[verify-license] %s\n' "$*"; }

engine_present=0

# 1. No CSQTT Rust sources may live in this repository.
while IFS= read -r rs_file; do
  [[ -n "$rs_file" ]] || continue
  fail "CSQTT/Rust source found in the repository: ${rs_file#$ROOT_DIR/}"
done < <(find "$ROOT_DIR" \
  \( -path "$ROOT_DIR/build" -o -path "$ROOT_DIR/.git" -o -path "$ROOT_DIR/.gradle" \) -prune -o \
  -name '*.rs' -type f -print 2>/dev/null)

# 2. Binaries installed into jniLibs.
if [[ -d "$JNI_DIR" ]]; then
  while IFS= read -r binary; do
    [[ -n "$binary" ]] || continue
    engine_present=1
    log "engine binary: ${binary#$ROOT_DIR/}"
  done < <(find "$JNI_DIR" -type f -name 'libcsqtt.so' -print 2>/dev/null)
fi

# 3. APKs passed explicitly.
apks=("$@")
for apk in "${apks[@]}"; do
  [[ -f "$apk" ]] || { fail "APK not found: $apk"; continue; }
  command -v unzip >/dev/null 2>&1 || { fail "unzip is required to inspect $apk"; continue; }

  if unzip -l "$apk" 2>/dev/null | grep -qE 'lib/[^/]+/libcsqtt\.so'; then
    engine_present=1
    log "engine binary in APK: $apk"
    unzip -l "$apk" 2>/dev/null | grep -q 'assets/licenses/CSQTT-LICENSE' || \
      fail "$apk contains libcsqtt.so but not assets/licenses/CSQTT-LICENSE"
    unzip -l "$apk" 2>/dev/null | grep -q 'assets/csqtt-engine.json' || \
      fail "$apk contains libcsqtt.so but not assets/csqtt-engine.json"
  else
    log "no engine binary in APK: $apk"
  fi
done

# 4. Required notices when the engine is shipped.
if [[ "$engine_present" == 1 ]]; then
  if [[ ! -f "$LICENSE_FILE" ]]; then
    fail "missing $LICENSE_FILE"
  else
    grep -qi 'PolyForm Noncommercial License 1\.0\.0' "$LICENSE_FILE" || \
      fail "$LICENSE_FILE does not contain the PolyForm Noncommercial 1.0.0 title"
    grep -q 'Required Notice:' "$LICENSE_FILE" || \
      fail "$LICENSE_FILE is missing upstream Required Notice lines"
    grep -qi 'amurcanov' "$LICENSE_FILE" || \
      fail "$LICENSE_FILE is missing the upstream copyright holder"
  fi

  if [[ ! -f "$NOTICES_FILE" ]]; then
    fail "missing $NOTICES_FILE"
  else
    grep -q 'CSQTT' "$NOTICES_FILE" || fail "$NOTICES_FILE has no CSQTT entry"
    grep -qi 'PolyForm' "$NOTICES_FILE" || fail "$NOTICES_FILE has no PolyForm mention"
    grep -q 'amurcanov' "$NOTICES_FILE" || fail "$NOTICES_FILE has no upstream author"
    grep -q 'Required Notice:' "$NOTICES_FILE" || fail "$NOTICES_FILE has no Required Notice lines"
  fi

  if [[ ! -f "$PROVENANCE_FILE" ]]; then
    fail "missing $PROVENANCE_FILE (engine provenance)"
  elif command -v jq >/dev/null 2>&1; then
    version="$(jq -r '.version // empty' "$PROVENANCE_FILE")"
    commit="$(jq -r '.commit // empty' "$PROVENANCE_FILE")"
    [[ -n "$version" ]] || fail "$PROVENANCE_FILE has no version"
    [[ "$commit" =~ ^[0-9a-f]{40}$ ]] || fail "$PROVENANCE_FILE has no 40-hex commit"
    [[ -n "$(jq -r '.license // empty' "$PROVENANCE_FILE")" ]] || fail "$PROVENANCE_FILE has no license id"
  else
    log "jq not found; skipping provenance content checks"
  fi
else
  log "CSQTT engine is not bundled; license notices are optional"
fi

if [[ "$ERRORS" -gt 0 ]]; then
  printf '[verify-license] FAILED with %s error(s)\n' "$ERRORS" >&2
  exit 1
fi

log "OK"
