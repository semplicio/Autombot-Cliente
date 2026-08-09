#!/usr/bin/env bash
set -euo pipefail

# Núcleo moderno usado por Hysteria2/TUIC no AutomBot Connect.
# Mantido fora do Git para não colocar um binário grande no histórico.
SING_BOX_VERSION="1.13.18"
ARCHIVE="sing-box-${SING_BOX_VERSION}-android-arm64.tar.gz"
URL="https://github.com/SagerNet/sing-box/releases/download/v${SING_BOX_VERSION}/${ARCHIVE}"
DEST_DIR="app/src/main/jniLibs/arm64-v8a"
DEST_FILE="${DEST_DIR}/libsingbox.so"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

echo "[AutomBot] Baixando sing-box ${SING_BOX_VERSION} Android arm64 do upstream oficial..."
curl --fail --location --proto '=https' --tlsv1.2 "$URL" -o "$TMP_DIR/$ARCHIVE"

tar -xzf "$TMP_DIR/$ARCHIVE" -C "$TMP_DIR"
SOURCE_BIN="$(find "$TMP_DIR" -type f -name sing-box -perm -u+x | head -n1 || true)"
if [[ -z "$SOURCE_BIN" ]]; then
    # Alguns tarballs podem perder o bit executável dependendo de como foram extraídos.
    SOURCE_BIN="$(find "$TMP_DIR" -type f -name sing-box | head -n1 || true)"
fi
if [[ -z "$SOURCE_BIN" ]]; then
    echo "[AutomBot] Erro: o binário sing-box não foi encontrado dentro do pacote." >&2
    exit 1
fi

mkdir -p "$DEST_DIR"
install -m 0755 "$SOURCE_BIN" "$DEST_FILE"

echo "[AutomBot] Núcleo instalado em $DEST_FILE"
file "$DEST_FILE" || true
"$DEST_FILE" version | head -n3

echo
printf '%s\n' \
  "[AutomBot] Próximo passo:" \
  "  ./gradlew clean :app:assembleDebug" \
  "  unzip -l app/build/outputs/apk/debug/app-debug.apk | grep libsingbox"
