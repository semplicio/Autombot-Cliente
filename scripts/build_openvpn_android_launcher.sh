#!/usr/bin/env bash
set -euo pipefail

# O core OpenVPN de ics-openvpn é uma shared library (libopenvpn.so) que exporta
# main(), não um executável. O upstream executa um PIE mínimo chamado
# libovpnexec.so, ligado contra libopenvpn.so. Este script reproduz exatamente
# essa separação e gera o launcher antes do APK ser empacotado.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ABI="arm64-v8a"
API_LEVEL="26"
NDK_VERSION="26.3.11579264"
DEST_DIR="app/src/main/jniLibs/${ABI}"
CORE_FILE="${DEST_DIR}/libopenvpn.so"
LAUNCHER_FILE="${DEST_DIR}/libovpnexec.so"

if [[ ! -s "$CORE_FILE" ]]; then
    echo "[AutomBot] Erro: core OpenVPN não encontrado em $CORE_FILE" >&2
    exit 1
fi

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
NDK_DIR=""
for candidate in \
    "${ANDROID_NDK_HOME:-}" \
    "${ANDROID_NDK_ROOT:-}" \
    "${SDK_ROOT:+$SDK_ROOT/ndk/$NDK_VERSION}"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then
        NDK_DIR="$candidate"
        break
    fi
done

if [[ -z "$NDK_DIR" && -n "$SDK_ROOT" && -d "$SDK_ROOT/ndk" ]]; then
    NDK_DIR="$(find "$SDK_ROOT/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1 || true)"
fi

if [[ -z "$NDK_DIR" || ! -d "$NDK_DIR" ]]; then
    echo "[AutomBot] Erro: Android NDK não encontrado. Configure ANDROID_NDK_HOME/ANDROID_NDK_ROOT ou instale o NDK $NDK_VERSION." >&2
    exit 1
fi

PREBUILT_DIR="$(find "$NDK_DIR/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n1 || true)"
if [[ -z "$PREBUILT_DIR" ]]; then
    echo "[AutomBot] Erro: toolchain LLVM do NDK não encontrado em $NDK_DIR" >&2
    exit 1
fi

CC="$PREBUILT_DIR/bin/aarch64-linux-android${API_LEVEL}-clang"
READELF="$PREBUILT_DIR/bin/llvm-readelf"
if [[ ! -x "$CC" ]]; then
    echo "[AutomBot] Erro: compilador Android arm64 não encontrado: $CC" >&2
    exit 1
fi

mkdir -p "$DEST_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
SOURCE_FILE="$TMP_DIR/minivpn.c"
OUTPUT_FILE="$TMP_DIR/libovpnexec.so"

# O minivpn.c do ics-openvpn upstream é intencionalmente vazio. O crt do PIE
# resolve main() a partir de libopenvpn.so.
: > "$SOURCE_FILE"

"$CC" \
    -fPIE \
    -pie \
    "$SOURCE_FILE" \
    -Wl,--no-as-needed \
    -L"$DEST_DIR" \
    -lopenvpn \
    -o "$OUTPUT_FILE"

if [[ ! -s "$OUTPUT_FILE" ]]; then
    echo "[AutomBot] Erro: launcher OpenVPN foi gerado vazio." >&2
    exit 1
fi

if [[ -x "$READELF" ]]; then
    if ! "$READELF" -d "$OUTPUT_FILE" | grep -q 'Shared library: \[libopenvpn.so\]'; then
        echo "[AutomBot] Erro: launcher não ficou ligado contra libopenvpn.so." >&2
        exit 1
    fi

    ENTRY_HEX="$("$READELF" -h "$OUTPUT_FILE" | awk '/Entry point address:/ {print $4; exit}')"
    if [[ -z "$ENTRY_HEX" || "$ENTRY_HEX" == "0x0" || "$ENTRY_HEX" == "0" ]]; then
        echo "[AutomBot] Erro: launcher OpenVPN não possui entry point executável." >&2
        exit 1
    fi
fi

install -m 0755 "$OUTPUT_FILE" "$LAUNCHER_FILE"

echo "[AutomBot] OpenVPN launcher pronto: $LAUNCHER_FILE"
if command -v file >/dev/null 2>&1; then
    file "$CORE_FILE" || true
    file "$LAUNCHER_FILE" || true
fi
