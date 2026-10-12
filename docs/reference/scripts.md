# Scripts

This page lists every script in the project: what it does, how to run it, which environment variables it reads,
and what it leaves on disk. Scripts are Bash scripts. They run on Linux, macOS, WSL and Git Bash on Windows.

| Script | Runs on | Purpose |
|---|---|---|
| [`scripts/tablet-debloat.sh`](#tablet-debloatsh) | Your computer | Hide or restore system apps on the tablet. |
| [`scripts/chatgpt-login.sh`](#chatgpt-loginsh) | Your computer | Sign in with ChatGPT and copy the session to the tablet. |
| [`scripts/nextcloud-ca.sh`](#nextcloud-cash) | Your computer | Import or remove the CA certificate for Nextcloud. |
| [`scripts/stt-pair.sh`](#stt-pairsh) | Your computer | Pair the tablet with the dictation service. |
| [`server/stt/setup.sh`](#serversttsetupsh) | The server | Create the certificate and token, and start the dictation service. |

## Common behavior

All the scripts that talk to the tablet need `adb` and a tablet connected over USB with USB debugging on. They
share these rules:

- `ADB` points at the `adb` binary. The default is `adb` from your path. In WSL, point it at the Windows
  `adb.exe`, because WSL 2 can't see USB devices. The scripts convert file paths for `.exe` programs.
- The scripts that hand a file to the app (`chatgpt-login.sh`, `nextcloud-ca.sh`, `stt-pair.sh`) work in the
  same way. They write the file to a private temporary folder on your computer, push it to
  `/data/local/tmp` on the tablet, and start Libreta's `AdbImportActivity`. The activity stores the file in the
  app's private storage and reports the result in `logcat` under the tag `LibretaImport`. The script waits up to
  20 seconds for that result. Only the `adb` shell user can write to `/data/local/tmp`, so the app imports only
  what you pushed.
- On exit, success or not, the scripts delete the temporary folder on your computer and the pushed copy on the
  tablet. The shared code is in `scripts/lib/adb-import.sh`.
- The import scripts exit with a non-zero status and a message on standard error when something fails. Typical causes
  are no tablet connected, Libreta not installed, or the tablet screen locked.

Keep the tablet screen unlocked while a script runs, because the app must start.

## `tablet-debloat.sh`

Hides or shows system packages with Android 4.4's `pm block`. The kiosk-mode page explains each group and its
risk: see [Kiosk mode](../setup/kiosk-mode.md).

```bash
scripts/tablet-debloat.sh block [GROUP...]     # hide; all groups when none is given
scripts/tablet-debloat.sh unblock [GROUP...]   # restore
```

| Argument | Meaning |
|---|---|
| `block` or `unblock` | The action. Required. |
| `GROUP` | One or more of `A` to `F`, as separate arguments, for example `block A B C`. Optional. |

| Variable | Default | Meaning |
|---|---|---|
| `ADB` | `adb` | The `adb` binary. |

The package lists are in the script, in the arrays `GROUP_A` to `GROUP_F`, and are for a Samsung Galaxy Tab 3 Lite
(SM-T113). The script prints each package that it can't change, including packages that don't exist on your device,
and a final summary such as `block: 79 ok, 1 failed`.

**Leaves on disk:** nothing. Hidden packages keep their data. Restore them with `unblock` or a factory reset.

## `chatgpt-login.sh`

Signs in with ChatGPT in your computer's browser and copies the session to the tablet. See
[Sign in with ChatGPT](../setup/chatgpt.md).

```bash
scripts/chatgpt-login.sh
```

The script runs `scripts/ChatGptLogin.java`, a single-file Java program without dependencies, with the Java you
point it at. `ChatGptLogin.java` registers the tablet, opens the browser, listens on
`http://127.0.0.1:1455/auth/callback`, checks the answer and writes the session to the file that the shell
script gives it. You can also run it on its own:

```bash
java scripts/ChatGptLogin.java session.json
```

| Variable | Default | Meaning |
|---|---|---|
| `JAVA` | `java` | The Java 11 or later binary. In WSL, use the Windows `java.exe`, so the browser's redirect reaches it. |
| `ADB` | `adb` | The `adb` binary. |

**Leaves on disk:**

- On your computer: `~/.libreta/chatgpt-client.json` with the tablet's host identifier and the client identifier
  that OpenAI issued. They aren't secrets, and later sign-ins reuse them. The session file is temporary and the
  script deletes it.
- On the tablet: `files/chatgpt-session.json` in the app's private storage.

## `nextcloud-ca.sh`

Imports or removes the CA certificate that Libreta trusts for Nextcloud. See
[Connect to Nextcloud](../setup/nextcloud.md#import-your-ca-certificate).

```bash
scripts/nextcloud-ca.sh ca.pem       # import
scripts/nextcloud-ca.sh --remove     # remove
```

| Argument | Meaning |
|---|---|
| `ca.pem` | A PEM file with one or more CA certificates. The import replaces any CA imported before. The script refuses a file that holds a private key, or no certificate. |
| `--remove` | Removes the imported CA. Libreta goes back to Android's system certificates. |

| Variable | Default | Meaning |
|---|---|---|
| `ADB` | `adb` | The `adb` binary. |

With an imported CA, Libreta trusts only those certificates for Nextcloud. Without one, it uses the system store.

**Leaves on disk:** on the tablet, `files/nextcloud-ca.pem` in the app's private storage. `--remove` deletes it.
Nothing stays on your computer.

## `stt-pair.sh`

Pairs the tablet with the dictation service. It reads the service's token and certificate from the server over
SSH and hands them to the app. Run it after `server/stt/setup.sh`, and again when the token or the certificate
changes. See [Deploy the dictation server](../setup/dictation-server.md).

```bash
SERVER=alice@192.0.2.10 STT_DIR=/home/alice/libreta-stt scripts/stt-pair.sh
```

| Variable | Default | Meaning |
|---|---|---|
| `SERVER` | Required | The SSH destination of the server, such as `alice@192.0.2.10`. Needs key-based login. |
| `STT_DIR` | Required | The absolute path of the `server/stt` folder on the server. |
| `STT_URL` | `https://<host of SERVER>:8443` | The address the tablet uses. Its host must be in the service certificate. |
| `ADB` | `adb` | The `adb` binary. |

The script needs `ssh` and `python3` on your computer.

**Leaves on disk:** on the tablet, `files/stt.json` in the app's private storage, with the address, the token and
the certificate. Nothing stays on your computer.

## `server/stt/setup.sh`

Creates the TLS certificate and the token, builds the container and starts the dictation service. Run it on the
server, in the copy of `server/stt`. See [Deploy the dictation server](../setup/dictation-server.md).

```bash
STT_IP=192.0.2.10 ./setup.sh
```

| Variable | Default | Meaning |
|---|---|---|
| `STT_IP` | Required on the first run | The IP address that goes into the certificate. Use the address the tablet uses. |
| `STT_NAME` | Empty | An extra DNS name for the certificate. |
| `STT_MODEL` | `large-v3-turbo` | The faster-whisper model. |
| `STT_THREADS` | One per CPU | The number of CPU threads. |
| `STT_LANGUAGE` | `es` | The language of the speech. |

The script needs Docker with the Compose plugin, `openssl` and `curl`. It uses the port 8443.

**Leaves on disk:** in the script's folder, `tls/key.pem`, `tls/cert.pem` and `token`, readable only by your user.
In Docker, the image `libreta-stt`, a container, and the volume
`libreta-stt-hf` with the downloaded models. Keep `tls/` and `token` out of version control.
