#!/usr/bin/env bash
set -e

KEYSTORE_FILE="release.jks"
KEY_ALIAS="camera1507d_release"

echo "=== Tao Keystore Release cho 1507DCamera ==="
if [ -f "$KEYSTORE_FILE" ]; then
    echo "Loi: File $KEYSTORE_FILE da ton tai!"
    exit 1
fi

keytool -genkeypair -v -keystore "$KEYSTORE_FILE" -alias "$KEY_ALIAS" -keyalg RSA -keysize 4096 -validity 10000

if command -v base64 >/dev/null 2>&1; then
    base64 -w 0 "$KEYSTORE_FILE" > release_keystore_base64.txt 2>/dev/null || base64 "$KEYSTORE_FILE" > release_keystore_base64.txt
    echo "Da tao chuoi Base64 luu tai release_keystore_base64.txt"
fi

echo "Hoan tat: $KEYSTORE_FILE"
