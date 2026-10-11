"""Dictation service for Libreta (docs/07-ia.md, part 2).

POST /v1/transcribe   body: WAV (16-bit PCM, mono), header Authorization: Bearer <token>
                      -> {"text": "...", "duration": seconds of audio, "took": seconds}
GET  /health          -> {"ok": true, "model": "..."}  (no token)

HTTPS only, with the certificate in /tls; the token is in /run/secrets/stt-token. The model is loaded once and
transcriptions run one at a time: each one already uses every thread it is given.
"""

import hmac
import io
import json
import logging
import os
import ssl
import threading
import time
import wave
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np
from faster_whisper import WhisperModel

MODEL = os.environ.get("STT_MODEL", "large-v3-turbo")
THREADS = int(os.environ.get("STT_THREADS", "12"))
LANGUAGE = os.environ.get("STT_LANGUAGE", "es")
PORT = int(os.environ.get("STT_PORT", "8443"))
TOKEN = open(os.environ.get("STT_TOKEN_FILE", "/run/secrets/stt-token")).read().strip()
# Five minutes of 16 kHz 16-bit mono is 9.6 MB; leave room for the header and a little more.
MAX_BODY = 12 * 1024 * 1024
SAMPLE_RATE = 16000

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
log = logging.getLogger("stt")
model = WhisperModel(MODEL, device="cpu", compute_type="int8", cpu_threads=THREADS)
busy = threading.Lock()
log.info("model %s loaded, %d threads", MODEL, THREADS)


def samples(wav_bytes):
    """The WAV as float32 samples at 16 kHz. Raises ValueError for anything else."""
    with wave.open(io.BytesIO(wav_bytes)) as w:
        if w.getsampwidth() != 2 or w.getnchannels() != 1 or w.getframerate() != SAMPLE_RATE:
            raise ValueError("expected 16-bit mono WAV at 16 kHz")
        pcm = w.readframes(w.getnframes())
    return np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0


class Handler(BaseHTTPRequestHandler):
    server_version = "libreta-stt"
    # A client that stops sending must not hold its thread forever.
    timeout = 60

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, {"ok": True, "model": MODEL})
        self.reply(404, {"error": "not_found"})

    def do_POST(self):
        if self.path != "/v1/transcribe":
            return self.reply(404, {"error": "not_found"})
        auth = self.headers.get("Authorization", "")
        if not hmac.compare_digest(auth.encode(), f"Bearer {TOKEN}".encode()):
            return self.reply(401, {"error": "unauthorized"})
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0 or length > MAX_BODY:
            return self.reply(413, {"error": "too_large"})
        try:
            audio = samples(self.rfile.read(length))
        except (ValueError, EOFError, wave.Error) as e:
            return self.reply(400, {"error": "bad_audio", "message": str(e)})
        started = time.time()
        with busy:
            segments, info = model.transcribe(audio, language=LANGUAGE, beam_size=5, vad_filter=True)
            text = " ".join(s.text.strip() for s in segments).strip()
        took = time.time() - started
        # Lengths only: what was said never goes to the log.
        log.info("transcribed %.1fs of audio in %.1fs, %d characters", info.duration, took, len(text))
        self.reply(200, {"text": text, "duration": round(info.duration, 2), "took": round(took, 2)})

    def reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        log.info("%s %s", self.address_string(), format % args)


def main():
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    # The tablet speaks TLS 1.2 through Conscrypt, with ECDHE and AES-GCM or ChaCha20.
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.set_ciphers("ECDHE+AESGCM:ECDHE+CHACHA20")
    context.load_cert_chain("/tls/cert.pem", "/tls/key.pem")
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    # The handshake happens in each request's thread, so a slow client cannot stall accept().
    server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    log.info("listening on %d", PORT)
    server.serve_forever()


if __name__ == "__main__":
    main()
