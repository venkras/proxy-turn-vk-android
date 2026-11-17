#!/usr/bin/env bash
# Build the pinned CSQTT engine for Android and install it into jniLibs.
#
# Usage:
#   bash scripts/build-csqtt-engine.sh [--fetch-only]
#
# Environment:
#   CSQTT_ENGINE_DIR        use local CSQTT sources instead of cloning
#   CSQTT_TARGET_ABIS       comma/space separated ABI list (default: lock file)
#   CSQTT_BUILD_JOBS        value for cargo --jobs
#   CSQTT_STRICT_TOOLCHAIN  1 = fail on Rust version mismatch
#   CSQTT_ALLOW_NDK_MISMATCH 1 = allow a different NDK revision
#   CSQTT_VERIFY            0 = skip verify-csqtt-engine.sh at the end
#
# The script never commits CSQTT sources: the managed checkout lives in
# build/csqtt-engine/src and is ignored by git.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOCK_FILE="$ROOT_DIR/scripts/csqtt-engine.lock.json"
FETCH_ONLY=0

for arg in "$@"; do
  case "$arg" in
    --fetch-only) FETCH_ONLY=1 ;;
    -h|--help)
      sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "error: unknown argument: $arg" >&2
      exit 2
      ;;
  esac
done

log() { printf '[csqtt] %s\n' "$*"; }
warn() { printf '[csqtt] warning: %s\n' "$*" >&2; }
die() { printf '[csqtt] error: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "required tool not found: $1"; }

[[ -f "$LOCK_FILE" ]] || die "lock file not found: $LOCK_FILE"
need jq

version="$(jq -r '.version // empty' "$LOCK_FILE")"
tag="$(jq -r '.tag // empty' "$LOCK_FILE")"
commit="$(jq -r '.commit // empty' "$LOCK_FILE")"
source_url="$(jq -r '.sourceUrl // empty' "$LOCK_FILE")"
archive_url="$(jq -r '.sourceArchive // empty' "$LOCK_FILE")"
archive_sha="$(jq -r '.sourceArchiveSha256 // empty' "$LOCK_FILE")"
rust_version="$(jq -r '.rust // empty' "$LOCK_FILE")"
cargo_ndk_version="$(jq -r '.cargoNdk // empty' "$LOCK_FILE")"
ndk_version="$(jq -r '.ndk // empty' "$LOCK_FILE")"
api_level="$(jq -r '.api // empty' "$LOCK_FILE")"
cargo_dir_rel="$(jq -r '.cargoDir // "rust-client"' "$LOCK_FILE")"
cargo_binary="$(jq -r '.cargoBinary // "client"' "$LOCK_FILE")"
binary_name="$(jq -r '.binaryName // "libcsqtt.so"' "$LOCK_FILE")"
max_size="$(jq -r '.maxSizeBytes // 12582912' "$LOCK_FILE")"

[[ -n "$version" ]] || die "lock file has no version"
[[ -n "$source_url" ]] || die "lock file has no sourceUrl"
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || die "lock file commit must be a 40-hex SHA, got: $commit"
[[ "$api_level" =~ ^[0-9]+$ ]] || die "lock file api must be a number"
[[ -n "$tag" ]] || tag="v$version"

requested_abis=()
if [[ -n "${CSQTT_TARGET_ABIS:-${TARGET_ABIS:-}}" ]]; then
  raw_abis="${CSQTT_TARGET_ABIS:-${TARGET_ABIS:-}}"
  read -r -a requested_abis <<< "${raw_abis//,/ }"
else
  while IFS= read -r abi; do
    [[ -n "$abi" ]] && requested_abis+=("$abi")
  done < <(jq -r '.abis[]' "$LOCK_FILE")
fi
[[ "${#requested_abis[@]}" -gt 0 ]] || die "no ABIs requested"

lock_abis=()
while IFS= read -r abi; do
  [[ -n "$abi" ]] && lock_abis+=("$abi")
done < <(jq -r '.abis[]' "$LOCK_FILE")
for abi in "${requested_abis[@]}"; do
  case "$abi" in
    arm64-v8a|armeabi-v7a|x86_64) ;;
    *) die "unsupported ABI '$abi' (allowed: ${lock_abis[*]})" ;;
  esac
  found=0
  for known in "${lock_abis[@]}"; do
    [[ "$abi" == "$known" ]] && found=1
  done
  [[ "$found" == 1 ]] || die "ABI '$abi' is not listed in the lock file"
done

triple_for() {
  case "$1" in
    arm64-v8a) echo "aarch64-linux-android" ;;
    armeabi-v7a) echo "armv7-linux-androideabi" ;;
    x86_64) echo "x86_64-linux-android" ;;
    *) return 1 ;;
  esac
}

# ----- source checkout -------------------------------------------------------

LOCAL_SOURCE=0
if [[ -n "${CSQTT_ENGINE_DIR:-}" ]]; then
  [[ -d "$CSQTT_ENGINE_DIR" ]] || die "CSQTT_ENGINE_DIR is not a directory: $CSQTT_ENGINE_DIR"
  SOURCE_DIR="$(cd "$CSQTT_ENGINE_DIR" && pwd)"
  LOCAL_SOURCE=1
else
  SOURCE_DIR="$ROOT_DIR/build/csqtt-engine/src"
fi

CARGO_DIR="$SOURCE_DIR/$cargo_dir_rel"
CARGO_MANIFEST="$CARGO_DIR/Cargo.toml"

verify_source_version() {
  local actual
  [[ -f "$CARGO_MANIFEST" ]] || die "CSQTT client crate not found: $CARGO_MANIFEST (expected lock file cargoDir '$cargo_dir_rel')"
  actual="$(sed -n 's/^version[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$CARGO_MANIFEST" | head -n 1)"
  if [[ -n "$actual" && "$actual" != "$version" ]]; then
    die "source version mismatch: Cargo.toml says '$actual', lock file says '$version'"
  fi
}

fetch_git_source() {
  need git
  mkdir -p "$(dirname "$SOURCE_DIR")"

  if [[ -d "$SOURCE_DIR/.git" ]]; then
    local current_url
    current_url="$(git -C "$SOURCE_DIR" remote get-url origin 2>/dev/null || true)"
    if [[ -n "$current_url" && "$current_url" != "$source_url" ]]; then
      die "existing checkout at $SOURCE_DIR points to '$current_url', expected '$source_url'; remove it or set CSQTT_ENGINE_DIR"
    fi
    log "updating existing checkout at $SOURCE_DIR"
  else
    if [[ -e "$SOURCE_DIR" ]] && [[ -n "$(ls -A "$SOURCE_DIR" 2>/dev/null || true)" ]]; then
      die "$SOURCE_DIR exists and is not a git checkout; remove it or set CSQTT_ENGINE_DIR"
    fi
    log "cloning $source_url"
    git clone --filter=blob:none --no-checkout "$source_url" "$SOURCE_DIR"
  fi

  log "fetching tag $tag"
  git -C "$SOURCE_DIR" fetch --force --depth 1 origin "+refs/tags/$tag:refs/tags/$tag"
  git -C "$SOURCE_DIR" -c advice.detachedHead=false checkout --detach "$commit"

  local actual tag_commit
  actual="$(git -C "$SOURCE_DIR" rev-parse HEAD)"
  [[ "$actual" == "$commit" ]] || die "checkout mismatch: HEAD=$actual, lock=$commit"
  tag_commit="$(git -C "$SOURCE_DIR" rev-parse "$tag^{commit}")"
  [[ "$tag_commit" == "$commit" ]] || die "tag $tag resolves to $tag_commit, lock expects $commit"
  log "source pinned at $commit ($tag)"
}

if [[ "$LOCAL_SOURCE" == 1 ]]; then
  if [[ -d "$SOURCE_DIR/.git" ]]; then
    actual_commit="$(git -C "$SOURCE_DIR" rev-parse HEAD 2>/dev/null || true)"
    if [[ -n "$actual_commit" && "$actual_commit" != "$commit" ]]; then
      warn "local source HEAD $actual_commit differs from lock commit $commit"
    fi
  else
    warn "local source has no .git; commit $commit cannot be verified (version check only)"
  fi
  verify_source_version
  log "using local source: $SOURCE_DIR"
else
  fetch_git_source
  verify_source_version
fi

if [[ -n "$archive_sha" ]]; then
  log "reference archive sha256: $archive_sha"
  [[ -n "$archive_url" ]] || warn "sourceArchiveSha256 is set but sourceArchive is empty"
fi

if [[ "$FETCH_ONLY" == 1 ]]; then
  log "fetch-only mode: $SOURCE_DIR is ready"
  exit 0
fi

# ----- toolchain -------------------------------------------------------------

need cargo
need rustc

cargo ndk --version >/dev/null 2>&1 || die "cargo-ndk is not installed (expected $cargo_ndk_version): cargo install cargo-ndk --version $cargo_ndk_version --locked"

rustc_actual="$(rustc --version | awk '{print $2}')"
if [[ -n "$rust_version" && "$rustc_actual" != "$rust_version" ]]; then
  if [[ "${CSQTT_STRICT_TOOLCHAIN:-0}" == "1" ]]; then
    die "Rust $rustc_actual does not match lock file ($rust_version)"
  fi
  warn "Rust $rustc_actual differs from lock file ($rust_version); set CSQTT_STRICT_TOOLCHAIN=1 to enforce"
fi

NDK_DIR="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK_DIR" && -f "$ROOT_DIR/local.properties" ]]; then
  SDK_DIR="$(grep -E '^sdk\.dir=' "$ROOT_DIR/local.properties" | head -n 1 | cut -d= -f2- || true)"
  if [[ -n "$SDK_DIR" && -d "$SDK_DIR/ndk" ]]; then
    NDK_DIR="$(find "$SDK_DIR/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
  fi
fi
if [[ -z "$NDK_DIR" && -n "${ANDROID_SDK_ROOT:-}" && -d "$ANDROID_SDK_ROOT/ndk" ]]; then
  NDK_DIR="$(find "$ANDROID_SDK_ROOT/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -n 1 || true)"
fi
[[ -n "$NDK_DIR" && -d "$NDK_DIR" ]] || die "Android NDK not found; set ANDROID_NDK_HOME to NDK $ndk_version"
export ANDROID_NDK_HOME="$NDK_DIR"

if [[ -f "$NDK_DIR/source.properties" ]]; then
  ndk_actual="$(sed -n 's/^Pkg\.Revision[[:space:]]*=[[:space:]]*//p' "$NDK_DIR/source.properties" | tr -d '\r' | head -n 1)"
  if [[ -n "$ndk_version" && -n "$ndk_actual" && "$ndk_actual" != "$ndk_version" ]]; then
    if [[ "${CSQTT_ALLOW_NDK_MISMATCH:-0}" == "1" ]]; then
      warn "NDK $ndk_actual differs from lock file ($ndk_version)"
    else
      die "NDK $ndk_actual does not match lock file ($ndk_version); set CSQTT_ALLOW_NDK_MISMATCH=1 to override"
    fi
  fi
fi

license_file="$(jq -r '.licenseFile // empty' "$LOCK_FILE")"
if [[ -n "$license_file" && ! -f "$ROOT_DIR/$license_file" ]]; then
  die "license notice is missing: $license_file (required by the upstream license)"
fi

# 16 KB page size is required for Android 15 devices. The upstream profile
# does not set it, so pass it through the linker for every target.
PAGE_SIZE_FLAG="-C link-arg=-Wl,-z,max-page-size=16384"
export RUSTFLAGS="${RUSTFLAGS:+$RUSTFLAGS }$PAGE_SIZE_FLAG"
export CMAKE_BUILD_PARALLEL_LEVEL="${CMAKE_BUILD_PARALLEL_LEVEL:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

TARGET_DIR="$ROOT_DIR/build/csqtt-engine/target"
JNI_DIR="$ROOT_DIR/app/src/main/jniLibs"
mkdir -p "$TARGET_DIR" "$JNI_DIR"

jobs="${CSQTT_BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 4)}"

log "Rust: $rustc_actual | NDK: ${ndk_actual:-$NDK_DIR} | API: $api_level"
log "crate: $CARGO_MANIFEST"
log "targets: ${requested_abis[*]}"

for abi in "${requested_abis[@]}"; do
  triple="$(triple_for "$abi")"
  log "building $abi ($triple) ..."
  # cargo-ndk runs `cargo metadata` in the current directory before building,
  # so the command must execute from the client crate root; otherwise Cargo
  # looks for Cargo.toml in the repository root and fails.
  # aws-lc-sys (pulled in by turn-rustls) builds C/asm through CMake.
  # The cmake crate maps the Rust target to CMAKE_SYSTEM_NAME=Android and
  # CMAKE_SYSTEM_PROCESSOR, and CMake's Android platform resolves the ABI
  # from that processor. Do NOT also set CMAKE_TOOLCHAIN_FILE: with the NDK
  # toolchain file present CMake would skip that mapping and fall back to
  # armeabi-v7a for every target. ANDROID_NDK* are needed so CMake and
  # aws-lc-sys find the pinned NDK non-interactively.
  (
    cd "$CARGO_DIR"
    ANDROID_NDK="$NDK_DIR" \
    ANDROID_NDK_ROOT="$NDK_DIR" \
      cargo ndk -t "$abi" -P "$api_level" build \
        --release \
        --locked \
        --jobs "$jobs" \
        --manifest-path "$CARGO_MANIFEST" \
        --target-dir "$TARGET_DIR"
  )

  artifact="$TARGET_DIR/$triple/release/$cargo_binary"
  [[ -f "$artifact" ]] || die "build finished but artifact is missing: $artifact"
  mkdir -p "$JNI_DIR/$abi"
  cp -f "$artifact" "$JNI_DIR/$abi/$binary_name"
  chmod 0755 "$JNI_DIR/$abi/$binary_name"
  size="$(wc -c < "$JNI_DIR/$abi/$binary_name" | tr -d ' ')"
  if [[ "$size" -gt "$max_size" ]]; then
    die "$abi/$binary_name is $size bytes, budget is $max_size bytes"
  fi
  log "installed $abi/$binary_name ($size bytes)"
done

log "writing provenance asset"
bash "$ROOT_DIR/scripts/csqtt-engine-provenance.sh" "${requested_abis[@]}"

if [[ "${CSQTT_VERIFY:-1}" == "1" ]]; then
  if command -v readelf >/dev/null 2>&1; then
    log "verifying engine binaries"
    CSQTT_TARGET_ABIS="${requested_abis[*]}" \
    CSQTT_REQUIRE_16K_PAGES="${CSQTT_REQUIRE_16K_PAGES:-1}" \
      bash "$ROOT_DIR/scripts/verify-csqtt-engine.sh"
  else
    warn "readelf not found; skipping binary verification (CI runs it on Linux)"
  fi
fi

log "done: ${requested_abis[*]}"
