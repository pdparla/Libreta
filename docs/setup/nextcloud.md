# Connect to Nextcloud

This page explains how to connect Libreta to the Notes app of a Nextcloud server. After you connect, every note
on the tablet is a file in your Nextcloud notes folder, and the other way around.

## Requirements

- A Nextcloud server with the Notes app installed.
- The server address must start with `https://`. Libreta doesn't connect over plain HTTP.
- The server must accept TLS 1.2 or later with AEAD cipher suites (AES-GCM or ChaCha20-Poly1305). This is the
  default in current Nextcloud installations. Libreta ships its own TLS provider (Conscrypt), so the old TLS
  stack of Android 4.4 doesn't limit you. For details, see [TLS](../design/tls.md).
- A certificate that the tablet can verify. If your server uses a private CA, or a certificate authority that
  Android 4.4 doesn't know, you must [import the CA certificate](#import-your-ca-certificate).

## Create an app password

Use an app password, not your account password. You can revoke an app password without changing your account
password.

1. Sign in to Nextcloud in a browser.
2. Open your profile menu, then **Personal settings** > **Security**.
3. In **Devices & sessions**, enter a name such as `Libreta`, and select **Create new app password**.
4. Copy the password. Nextcloud shows it only once.

## Enter the connection settings

1. On the tablet, open Libreta and then «Ajustes» (Settings).
2. Fill in the fields:

   | Field | What to enter |
   |---|---|
   | «Dirección del servidor» (Server address) | The address of your server, such as `https://nextcloud.example.com`. It must start with `https://`. |
   | «Usuario de Nextcloud» (Nextcloud user) | Your Nextcloud user name, such as `alice`. |
   | «Contraseña de aplicación» (App password) | The app password that you created. |
   | «Carpeta de notas» (Notes folder) | The folder where the Notes app stores its notes. The connection test fills this in for you. |
   | «Nombre del certificado» (Certificate name) | Optional. See [Connect by IP address](#connect-by-ip-address). |

3. Tap «Probar conexión» (Test connection).
4. When the test succeeds, tap «Guardar» (Save). Libreta saves the settings and starts a sync.

Libreta stores the password in the app's private storage and never shows it again. When you open the settings
later, leave the password field empty to keep the saved password. If you change the server, the user or the
notes folder, Libreta clears its sync state and syncs from scratch.

### The notes folder

The Notes app stores its notes in a folder that you can change in the Notes app settings. The default is
`Notes`, but it can have another name, for example in a different language. Libreta must use the same folder, or
your notes don't show up in the Notes app.

When you tap «Probar conexión», Libreta asks the Notes app which folder it uses
(`/index.php/apps/notes/api/v1/settings`) and fills in the «Carpeta de notas» field. If the folder doesn't exist
on the server, the sync stops without changing anything. Libreta never reads a missing folder as "all notes were
deleted".

### What the connection test checks

The test checks the TLS connection, your credentials and the notes folder, in that order. The result appears
below the buttons.

| Message | Meaning |
|---|---|
| «Conexión correcta: TLS verificado, credenciales aceptadas…» (Connection successful) | Everything works. Tap «Guardar». |
| «TLS correcto con el servidor. Falta la contraseña de aplicación.» | TLS works, but you didn't enter the app password. |
| «usuario o contraseña de aplicación incorrectos» | Nextcloud rejected the user name or the app password. |
| «no existe la carpeta de notas en Nextcloud» | The notes folder doesn't exist. Check the folder name. |
| «el certificado no está emitido al nombre esperado» | The certificate name doesn't match. See [Connect by IP address](#connect-by-ip-address). |
| A message about the CA or the certificate | The tablet doesn't trust the server's certificate. See [Import your CA certificate](#import-your-ca-certificate). |
| «no se puede conectar con el servidor» | The tablet can't reach the server. Check the address and the Wi-Fi connection. |
| «Nextcloud está en modo mantenimiento» | The server is in maintenance mode. |

## Import your CA certificate

Libreta decides whom to trust for Nextcloud in one of two ways:

- **No imported CA.** Libreta uses the certificates of the Android system. The store on Android 4.4 dates from
  2017 and lacks newer roots, such as ISRG Root X1 from Let's Encrypt.
- **Imported CA.** Libreta trusts only the certificates that you import. It ignores the system store for
  Nextcloud.

Import a CA certificate if your server uses a private CA, or a public CA that Android 4.4 doesn't know.

The «Ajustes» screen shows which source is in use: the name of the imported CA, or the system certificates.

### Import a certificate

You need the CA certificate as a PEM file. The file contains one or more `-----BEGIN CERTIFICATE-----` blocks.
To trust several CAs, put all the blocks in one file.

1. Connect the tablet to your computer with USB debugging enabled. See [Build and install](build.md).
2. Run the import script:

   ```bash
   scripts/nextcloud-ca.sh my-ca.pem
   ```

   The script copies the file to the tablet, and Libreta stores it as `files/nextcloud-ca.pem` in its private
   storage. A message on the tablet confirms the import. A new import replaces any CA that you imported before.
   The script refuses a file that contains a private key.

3. Open «Ajustes» on the tablet and tap «Probar conexión».

If the `adb` binary is not on your path, or you work in WSL, set `ADB`. See
[Scripts](../reference/scripts.md#nextcloud-cash).

### Use a Let's Encrypt certificate

Android 4.4 doesn't include ISRG Root X1, the root of the Let's Encrypt chain. Import it with the same script:

1. Download the certificate from the
   [Let's Encrypt certificate list](https://letsencrypt.org/certificates/). The PEM file for ISRG Root X1 is
   `https://letsencrypt.org/certs/isrgrootx1.pem`.
2. Import it:

   ```bash
   scripts/nextcloud-ca.sh isrgrootx1.pem
   ```

If your chain ends in a different root, such as ISRG Root X2, import that one too. You can put several
certificates in the same file.

### Remove the imported CA

To go back to the system certificates, run:

```bash
scripts/nextcloud-ca.sh --remove
```

## Connect by IP address

Android 4.4 doesn't resolve `.local` names, because it has no mDNS. If you can't reach your server by a name
that the tablet can resolve, you can connect by IP address. But a certificate usually names a host, not an IP
address, so the standard host name check fails.

The «Nombre del certificado» (Certificate name) setting solves this. Enter the name that your server's certificate
was issued to, for example `nextcloud.home.arpa`. Libreta then accepts the server when both of these are true:

- A trusted CA signed the certificate. With an imported CA, this means the imported CA.
- The certificate was issued to the name you entered, whatever host or IP address the tablet dialed.

The «Nombre del certificado» setting is also useful for certificates without a Subject Alternative Name (SAN),
which Android's standard check rejects. Leave the field empty to use the standard host name check.

## Sync

After you save the settings, Libreta syncs:

- when you open the app,
- when you move the app to the background (rotating the screen doesn't count),
- when you tap «Sincronizar» (Sync),
- when you save the settings.

If the tablet has no network, the sync stops without changing anything and continues the next time. For how
Libreta resolves conflicts, see [Sync](../design/sync.md).

## Next steps

- [Sign in with ChatGPT](chatgpt.md)
- [Deploy the dictation server](dictation-server.md)
