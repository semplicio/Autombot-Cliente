#!/usr/bin/env bash
set -euo pipefail

# O core OpenVPN de ics-openvpn é uma shared library (libopenvpn.so) que exporta
# main(), não um executável. O app precisa iniciar um PIE mínimo (libovpnexec.so)
# ligado contra libopenvpn.so.
#
# O NDK oficial usado pelo projeto fornece ferramentas host x86_64. Em máquinas
# Linux ARM64 elas acabam passando por QEMU/binfmt e o clang pode encerrar com
# SIGSEGV (exit 139). Por isso o launcher, que tem apenas 5 KiB e não muda em
# runtime, é pré-gerado/validado em CI x86_64 e armazenado como Base64 textual.
# O build local apenas restaura o ELF, sem executar toolchain x86 via QEMU.
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

ABI="arm64-v8a"
DEST_DIR="app/src/main/jniLibs/${ABI}"
CORE_FILE="${DEST_DIR}/libopenvpn.so"
LAUNCHER_FILE="${DEST_DIR}/libovpnexec.so"
PAYLOAD_FILE="scripts/prebuilt/openvpn/libovpnexec-arm64-v8a.b64"
EXPECTED_SHA256="7247604e0f5d650876ccdeeded20333a0040464b771906406cde0861489e08d7"

if [[ ! -s "$CORE_FILE" ]]; then
    echo "[AutomBot] Erro: core OpenVPN não encontrado em $CORE_FILE" >&2
    exit 1
fi

if [[ ! -s "$PAYLOAD_FILE" ]]; then
    echo "[AutomBot] Erro: payload do launcher OpenVPN não encontrado em $PAYLOAD_FILE" >&2
    exit 1
fi

if ! command -v base64 >/dev/null 2>&1; then
    echo "[AutomBot] Erro: utilitário base64 não encontrado no host de build." >&2
    exit 1
fi

mkdir -p "$DEST_DIR"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT
OUTPUT_FILE="$TMP_DIR/libovpnexec.so"

base64 --decode "$PAYLOAD_FILE" > "$OUTPUT_FILE"

if [[ ! -s "$OUTPUT_FILE" ]]; then
    echo "[AutomBot] Erro: launcher OpenVPN restaurado ficou vazio." >&2
    exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(sha256sum "$OUTPUT_FILE" | awk '{print $1}')"
elif command -v shasum >/dev/null 2>&1; then
    ACTUAL_SHA256="$(shasum -a 256 "$OUTPUT_FILE" | awk '{print $1}')"
else
    echo "[AutomBot] Erro: sha256sum/shasum não encontrado para validar o launcher." >&2
    exit 1
fi

if [[ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]]; then
    echo "[AutomBot] Erro: checksum do launcher OpenVPN inválido." >&2
    echo "[AutomBot] Esperado: $EXPECTED_SHA256" >&2
    echo "[AutomBot] Obtido:  $ACTUAL_SHA256" >&2
    exit 1
fi

# Validação estrutural adicional usa o readelf nativo do host, quando disponível,
# e portanto não executa ferramentas x86_64 do NDK em hosts ARM64.
if command -v readelf >/dev/null 2>&1; then
    if ! readelf -d "$OUTPUT_FILE" | grep -q 'Shared library: \[libopenvpn.so\]'; then
        echo "[AutomBot] Erro: launcher pré-gerado não depende de libopenvpn.so." >&2
        exit 1
    fi

    ENTRY_HEX="$(readelf -h "$OUTPUT_FILE" | awk '/Entry point address:/ {print $4; exit}')"
    if [[ -z "$ENTRY_HEX" || "$ENTRY_HEX" == "0x0" || "$ENTRY_HEX" == "0" ]]; then
        echo "[AutomBot] Erro: launcher OpenVPN não possui entry point executável." >&2
        exit 1
    fi
fi

install -m 0755 "$OUTPUT_FILE" "$LAUNCHER_FILE"

echo "[AutomBot] OpenVPN launcher restaurado do payload CI: $LAUNCHER_FILE"
echo "[AutomBot] SHA-256 validado: $EXPECTED_SHA256"
if command -v file >/dev/null 2>&1; then
    file "$CORE_FILE" || true
    file "$LAUNCHER_FILE" || true
fi
