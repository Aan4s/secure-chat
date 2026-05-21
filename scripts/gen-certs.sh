#!/usr/bin/env bash
# Generates a self-signed certificate for the server, and a client truststore that trusts it.
#
# Usage: ./scripts/gen-certs.sh [output_dir]   (default: certs/)
# Env:   SECURECHAT_KEYSTORE_PASSWORD (default: changeit)
#        SECURECHAT_SERVER_CN         (default: localhost)
set -euo pipefail

CERT_DIR="${1:-certs}"
PASSWORD="${SECURECHAT_KEYSTORE_PASSWORD:-changeit}"
HOSTNAME="${SECURECHAT_SERVER_CN:-localhost}"

mkdir -p "$CERT_DIR"

if [[ -f "$CERT_DIR/server.p12" ]]; then
    echo "Refusing to overwrite existing $CERT_DIR/server.p12 — delete it first if you want to regenerate."
    exit 1
fi

echo "Generating server keystore (4096-bit RSA, valid 365 days, CN=$HOSTNAME)..."
keytool -genkeypair \
    -alias server \
    -keyalg RSA \
    -keysize 4096 \
    -sigalg SHA256withRSA \
    -validity 365 \
    -storetype PKCS12 \
    -keystore "$CERT_DIR/server.p12" \
    -storepass "$PASSWORD" \
    -dname "CN=$HOSTNAME, O=SecureChat, L=Local, C=FR" \
    -ext "san=dns:$HOSTNAME,ip:127.0.0.1"

echo "Exporting server public certificate..."
keytool -exportcert \
    -alias server \
    -keystore "$CERT_DIR/server.p12" \
    -storepass "$PASSWORD" \
    -file "$CERT_DIR/server.crt"

echo "Building client truststore..."
keytool -importcert \
    -alias server \
    -file "$CERT_DIR/server.crt" \
    -keystore "$CERT_DIR/client-truststore.p12" \
    -storetype PKCS12 \
    -storepass "$PASSWORD" \
    -noprompt

echo
echo "Done. Files in $CERT_DIR/:"
echo "  server.p12             — server keystore (KEEP PRIVATE, password: $PASSWORD)"
echo "  server.crt             — server public certificate"
echo "  client-truststore.p12  — client truststore (trusts the server cert)"
echo
echo "The keystore password is in the SECURECHAT_KEYSTORE_PASSWORD env var (default: changeit)."
echo "Change it for any non-local deployment."
