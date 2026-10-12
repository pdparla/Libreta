# Deploy the dictation server

This page explains how to run Libreta's voice dictation service on your own server and pair the tablet with it.
You do this only if you want the microphone button in the editor, the handwritten note and the chat. For how to
use dictation, see [Voice dictation](../guide/dictation.md).

## Why a server

Your ChatGPT plan doesn't include a transcription API, and the tablet's processor is too slow for a speech
model. So the tablet records a WAV file, sends it to a service that you run, and the service returns the text.
The service runs [faster-whisper](https://github.com/SYSTRAN/faster-whisper) on the CPU, in a Docker container.
No audio or text goes to a third party.

## Requirements

On the server:

- Linux with Docker and the Docker Compose plugin. Rootless Docker works.
- `openssl` and `curl`.
- About 4 GB of free RAM while it transcribes, and about 2 GB of disk space for the image and the model.
- An address the tablet can reach on TCP port 8443. Open the port in your firewall if needed.

On your computer:

- `ssh` access to the server with a key (the pairing script doesn't ask for a password).
- `python3` and `adb`, with the tablet connected over USB.

A CPU-only server transcribes a minute of speech in roughly 15 seconds with 12 threads. The service skips the
silences, so recordings with pauses are faster.

## Deploy the service

1. Copy the `server/stt` folder to the server. The key and the token that the service creates stay in that
   folder, so copy it somewhere that is not a Git repository:

   ```bash
   scp -r server/stt alice@192.0.2.10:libreta-stt
   ```

2. On the server, run the setup script. Set `STT_IP` to the address that the tablet uses to reach the server:

   ```bash
   cd libreta-stt
   STT_IP=192.0.2.10 ./setup.sh
   ```

   If the tablet reaches the server by a DNS name, also set `STT_NAME`, for example
   `STT_NAME=stt.home.arpa`. The name goes into the certificate.

3. Wait for the script to finish. The first run downloads the model (1.6 GB), so it can take several minutes.
   The script ends by printing the answer of the health endpoint, which looks like this:

   ```json
   {"ok": true, "model": "large-v3-turbo"}
   ```

The script is safe to run again. Run it after you change `app.py` or the model.

### What the script creates

| Item | Where | Purpose |
|---|---|---|
| `tls/key.pem` and `tls/cert.pem` | The `libreta-stt` folder | A self-signed ECDSA P-256 certificate, valid for 10 years, with `STT_IP` (and `STT_NAME`) in the Subject Alternative Name. |
| `token` | The `libreta-stt` folder | A random 256-bit token that the tablet sends as a bearer token. |
| Docker volume `libreta-stt-hf` | Docker | The downloaded Whisper models. It survives rebuilds. |
| Docker image `libreta-stt` and a container | Docker | The service. The container restarts automatically. |

The script creates the certificate and the token only if they don't exist. Only your user can read the key and
the token.

### Service options

Set these environment variables when you run `./setup.sh`:

| Variable | Default | Meaning |
|---|---|---|
| `STT_IP` | None | Required the first time. The IP address that goes into the certificate. |
| `STT_NAME` | Empty | An extra DNS name for the certificate. |
| `STT_MODEL` | `large-v3-turbo` | The faster-whisper model. Smaller models are faster and less accurate. |
| `STT_THREADS` | One per CPU | The number of CPU threads. |
| `STT_LANGUAGE` | `es` | The language of the speech. The service uses a fixed language. |

### Security

- The service speaks only HTTPS, with TLS 1.2 or later and ECDHE with AES-GCM or ChaCha20, the suites that the
  tablet supports.
- Every request needs the token, except `GET /health`.
- The tablet trusts only the certificate that you pair with it.
- The service rejects bodies larger than 12 MB and logs only durations and lengths, never the dictated text.

Don't expose port 8443 to the internet. Keep the service on your home or office network.

## Pair the tablet

The pairing script reads the token and the public certificate from the server over SSH and hands them to Libreta
over `adb`. The private key never leaves the server.

1. Connect the tablet to your computer with USB debugging enabled.
2. Run the script. `SERVER` is the SSH destination and `STT_DIR` is the folder that you copied to the server:

   ```bash
   SERVER=alice@192.0.2.10 STT_DIR=/home/alice/libreta-stt scripts/stt-pair.sh
   ```

   By default, the tablet uses `https://192.0.2.10:8443`, the host of `SERVER` with port 8443. If the tablet
   should use another address, set `STT_URL`. The host in `STT_URL` must be `STT_IP` or `STT_NAME`, because the
   certificate is checked against it.

3. On the tablet, open «Ajustes» (Settings). The «Dictado» (Dictation) section shows the service address.
4. Tap «Probar dictado» (Test dictation). A successful test shows the model name.

Libreta stores the pairing as `files/stt.json` in its private storage. For what the script leaves behind, see
[Scripts](../reference/scripts.md#stt-pairsh).

## Change the token or the certificate

Because the tablet trusts one certificate, a new certificate or token needs a new pairing, not a new app build.

1. On the server, delete what you want to replace: `token`, or the `tls` folder, or both.
2. Run `STT_IP=192.0.2.10 ./setup.sh` again, and restart the container so it reads the new token:

   ```bash
   docker compose restart
   ```

3. Pair the tablet again with `scripts/stt-pair.sh`.

## Troubleshooting

| Problem | What to do |
|---|---|
| `setup.sh` asks for `STT_IP`. | Set `STT_IP` to the server address that the tablet uses. |
| The setup script ends without an answer from the service. | Run `docker compose logs`. The first start can take long while the model downloads. |
| «Probar dictado» fails. | Check that the tablet can reach port 8443 on the server and that the host in `STT_URL` is in the certificate. |
| Dictation says that the service doesn't accept the token. | Pair again with `scripts/stt-pair.sh`. |
| Dictation says it isn't configured. | Run `scripts/stt-pair.sh` with the tablet connected. |

## Next steps

- [Voice dictation](../guide/dictation.md)
- [AI design](../design/ai.md), for how the service and the client work.
