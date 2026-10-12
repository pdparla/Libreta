# Settings

Open the settings with the gear icon («Ajustes», Settings) in the top bar of the note list. The screen has four groups: the Nextcloud connection, the tablet, dictation, and the AI.

For a first-time walkthrough of the Nextcloud group, see [Connect to Nextcloud](../setup/nextcloud.md).

## Nextcloud connection

| Setting | What it does |
|---|---|
| «Dirección del servidor» (Server address) | The address of your Nextcloud server, for example `https://nextcloud.example.com`. It must start with `https://`. The app never uses plain HTTP. |
| «Usuario de Nextcloud» (Nextcloud user) | Your Nextcloud user name, for example `alice`. |
| «Contraseña de aplicación» (App password) | A Nextcloud app password, not your account password. Create one in Nextcloud under *Settings → Security → Devices & sessions*. The app never shows a saved password. When one is saved, the field says «Guardada · déjalo vacío para no cambiarla» (Saved · leave empty to keep it). Leave the field empty to keep it. |
| «Carpeta de notas» (Notes folder) | The folder in your Nextcloud files that holds the notes. The default is `Notes`, which is the folder of the Nextcloud Notes app. |
| «Nombre del certificado» (Certificate name) | Optional. See [Certificate name](#certificate-name). |
| Trust source (a status line) | Shows which certificates the tablet trusts for Nextcloud. See [Trust source](#trust-source). |
| «Probar conexión» (Test connection) | Checks the connection without saving anything. |
| «Guardar» (Save) | Saves the settings and starts a sync. |

### Test connection

«Probar conexión» checks the TLS connection, the credentials and the notes folder. It shows «Probando…» (Testing…) and then one of these:

| Message | Meaning |
|---|---|
| «Conexión correcta: TLS verificado y credenciales aceptadas. Carpeta de la app Notes de Nextcloud: «Notes». Pulsa Guardar.» | The test succeeded. The folder name is the one the Nextcloud Notes app is set to use. Libreta fills the «Carpeta de notas» field with it so your notes show up there. |
| «Conexión correcta: TLS verificado, credenciales aceptadas y carpeta de notas encontrada.» | The test succeeded and Libreta could not read the Notes app folder, so it kept yours. |
| «TLS correcto con el servidor. Falta la contraseña de aplicación.» | The secure connection works, but you have not entered a password. |
| An error, for example «Usuario o contraseña de aplicación incorrectos» | See [Connection errors](#connection-errors). |

### Save

«Guardar» checks the form first:

- If the address does not start with `https://` or the user is empty, the message is «La dirección debe empezar por https:// y el usuario no puede estar vacío.»
- If the address, user or password is missing, the message is «Faltan datos: dirección https://, usuario y contraseña de aplicación.»

If the form is valid, Libreta saves it, starts a sync and returns to the list. When you change the server, the user or the folder, Libreta forgets what it knows about the previous sync and compares both sides from scratch. A note that exists on only one side is copied to the other, so the notes on the tablet are uploaded to the new location.

The password is stored in the app's private storage on the tablet. Backups are turned off for the app. Use an app password, which you can revoke in Nextcloud at any time.

### Trust source

Libreta connects to Nextcloud over TLS 1.2 with strong (ECDHE and AEAD) cipher suites only. The settings show which certificate authorities (CAs) it trusts for that connection:

- **Imported CA.** If you imported a CA certificate with `scripts/nextcloud-ca.sh`, the status line reads «Certificados de confianza: CA importada (…)» (Trusted certificates: imported CA) with the name of that CA. The tablet then trusts **only** the certificates you imported for Nextcloud.
- **System certificates.** If you imported nothing, the status line reads «Certificados de confianza: los del sistema» (Trusted certificates: the system's). Android 4.4 has an old list of CAs (from 2017). For example, it does not have the Let's Encrypt root *ISRG Root X1*. If your server uses Let's Encrypt, import that root with the same script.

See [Connect to Nextcloud](../setup/nextcloud.md) for the steps, and [TLS](../design/tls.md) for the design.

### Certificate name

Leave «Nombre del certificado» empty in most cases. Then Libreta checks that the server certificate matches the host name in the address, as usual.

Fill it in only when the certificate is issued to a different name than the address. This happens when you connect by IP address, or by a name that the tablet cannot resolve, for example a `.local` name, because Android 4.4 has no mDNS. Type the name in the certificate, for example `nextcloud.home.arpa`. Libreta then accepts the server if its certificate is issued to that name, whatever address you used. The certificate must still be signed by a CA that the tablet trusts, so a certificate for another name or from another CA is rejected.

### Connection errors

| Message | Cause |
|---|---|
| «usuario o contraseña de aplicación incorrectos» | The server rejected the credentials (HTTP 401). |
| «no existe la carpeta de notas en Nextcloud» | The notes folder does not exist (HTTP 404). |
| «Nextcloud está en modo mantenimiento» | The server is in maintenance mode (HTTP 503). |
| «el servidor respondió HTTP N» | Another HTTP error. |
| «el certificado no está emitido al nombre esperado» | The certificate name does not match. See [Certificate name](#certificate-name). |
| «el servidor no presenta un certificado de la CA de confianza» | The certificate is not signed by a trusted CA. See [Trust source](#trust-source). |
| «error de TLS» | Another TLS error. |
| «no se puede conectar con el servidor» | The server cannot be reached. Check the address and the network. |
| «no se pudo cargar el cifrado (Conscrypt)» | The encryption library could not be loaded. |
| «la configuración del servidor no es válida» | The address or the user is not valid. |

## Tablet

| Setting | What it does |
|---|---|
| «Usar Libreta como pantalla de inicio» (Use Libreta as the home screen) | When on, the Home button always opens Libreta. After you turn it on, Android asks you to choose a home app: choose «Libreta» and «Siempre» (Always). When you turn it off, the Samsung home screen returns. While the note list is the home screen, the back button does nothing there. See [Kiosk mode](../setup/kiosk-mode.md). |
| «Ajustes de Android» (Android settings) | Opens the Android settings of the tablet. |

## Dictation

This group is titled «Dictado».

| Control | What it does |
|---|---|
| Status line | Shows «Servicio: https://…», the address of the paired dictation service. If the tablet is not paired, it shows «El dictado no está configurado» and the command to pair it. |
| «Probar dictado» (Test dictation) | Contacts the service. On success it says «Conexión correcta con el servicio de dictado. Modelo: large-v3-turbo.» (the model name depends on your server). On failure it says «Sin conexión con el servicio de dictado: …» with the reason. The button is off until the tablet is paired. |

There is nothing to type here. The address, the token and the certificate come from pairing. See [Set up the dictation server](../setup/dictation-server.md) and [Voice dictation](../guide/dictation.md).

## AI (ChatGPT)

This group is titled «IA (ChatGPT)».

| Control | What it does |
|---|---|
| Status line | When you are signed in: «Sesión iniciada como alice@example.com. Conversar: gpt-6.1-sol (razonamiento medium). Convertir a texto: gpt-6.1-sol (razonamiento medium).» It shows your ChatGPT account and the model and reasoning level that Libreta uses for chat and for converting handwriting. When you are not signed in, it says «Sin sesión» and how to sign in. |
| «Cerrar sesión» (Sign out) | After you confirm («¿Cerrar la sesión de ChatGPT en esta tableta?»), Libreta revokes the session at OpenAI and deletes it from the tablet. It says «Sesión cerrada y revocada en OpenAI.» If the revocation cannot be confirmed, the session is still deleted, and the message says «Sesión borrada de la tableta, pero no se pudo confirmar la revocación en OpenAI. Puedes desconectar Libreta desde los ajustes de ChatGPT.» (Session deleted from the tablet, but the revocation at OpenAI could not be confirmed. You can disconnect Libreta in the ChatGPT settings.) The button is off when you are not signed in. |
| «Transcribir lo escrito a mano para buscarlo» (Read handwriting so it can be searched) | On by default. When on, Libreta reads your handwritten pages in the background so that search finds them. It uses your ChatGPT plan, one request per page that changed. When off, handwriting is not searched. See [Search handwriting](../guide/notes.md#search-handwriting). |
| Pages status line | Under the checkbox. It says «N páginas pendientes de transcribir» (N pages are waiting to be read), or «Una página pendiente de transcribir». |

You cannot sign in from the app. Sign in from a computer. See [Sign in with ChatGPT](../setup/chatgpt.md).

## Settings that are not on this screen

Libreta remembers these on its own, in every note:

- the pen colour and width;
- the eraser mode («Donde toco» or «Trazo entero») and size.

See [Handwritten notes](../guide/handwriting.md).
