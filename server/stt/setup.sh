#!/usr/bin/env bash
# Sets up and starts Libreta's dictation service on the server (docs/setup/dictation-server.md). Run it on the
# server, from this folder or anywhere: it works in the folder it lives in.
#
#   STT_IP=192.0.2.10 server/stt/setup.sh     # first time, and after changing app.py or the model
#
# STT_IP is the address the tablet uses to reach the server; STT_NAME optionally adds a DNS name to the
# certificate. Both are only used the first time, to create the certificate.
#
# The first time it creates the TLS key and certificate (ECDSA P-256, 10 years, the server's IP and name in the SAN)
# and the token. They stay here, outside git; scripts/stt-pair.sh hands the certificate and the token to the
# tablet. STT_MODEL, STT_THREADS and STT_LANGUAGE override the model, the threads
# (default: one per CPU) and the language (default: es).
set -euo pipefail
cd "$(dirname "$0")"
IP=${STT_IP:-}
NAME=${STT_NAME:-}

umask 077
if [ ! -f tls/key.pem ]; then
  [ -n "$IP" ] || { echo "Set STT_IP to the server's address as the tablet sees it, e.g. STT_IP=192.0.2.10" >&2; exit 2; }
  SAN="IP:$IP${NAME:+,DNS:$NAME}"
  mkdir -p tls
  openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:prime256v1 -nodes -days 3650 \
    -keyout tls/key.pem -out tls/cert.pem -subj "/CN=libreta-stt" \
    -addext "subjectAltName=$SAN" -addext "extendedKeyUsage=serverAuth" 2> /dev/null
  echo "TLS certificate created."
fi
if [ ! -f token ]; then
  openssl rand -base64 32 | tr -d '\n=' | tr '+/' '-_' > token
  echo "Token created."
fi
# Only this user: rootless Docker runs the container as this same user.
chmod 700 tls && chmod 600 tls/key.pem token && chmod 644 tls/cert.pem

docker volume create libreta-stt-hf > /dev/null
docker compose up -d --build
echo "Waiting for the model to load..."
for _ in $(seq 1 120); do
  if curl -sfk "https://127.0.0.1:8443/health" > /dev/null 2>&1; then
    curl -sk "https://127.0.0.1:8443/health"; echo
    exit 0
  fi
  sleep 5
done
echo "The service did not answer; see: docker compose logs" >&2
exit 1
