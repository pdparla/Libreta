# Security policy

## Report a vulnerability

Report vulnerabilities privately. Do not open a public issue or pull request for them.

1. Open the **Security** tab of the repository on GitHub.
2. Select **Report a vulnerability**. This creates a private security advisory that only you and the maintainers can
   see.
3. Describe the problem, the steps to reproduce it, the version or commit you tested, and the impact you expect.

The maintainers will acknowledge the report, work with you on a fix, and credit you in the advisory if you want. Please
give them reasonable time to publish a fix before you disclose the problem.

## Supported versions

Libreta is developed on one branch. Security fixes go to the latest commit on `main`.

## Scope

The following are in scope:

- how the app verifies TLS connections and which certificates it trusts;
- how the app stores and sends tokens and passwords;
- the dictation service in `server/stt`;
- the scripts in `scripts/`, which move secrets between your computer, a server and the tablet;
- the exported `AdbImportActivity`.

Problems in Android 4.4 itself, in Nextcloud, or in OpenAI's services are out of scope. Report those to their
vendors.

## What the app stores on the device

Everything is in the app's private storage, and the app sets `allowBackup=false`, so Android does not copy it to a
backup. The tablet is not expected to be rooted.

| Data | Where | Notes |
|---|---|---|
| Nextcloud address, user and app password | Private `SharedPreferences` (`server`) | The password is stored in clear text inside the app sandbox. Android 4.4 has no Keystore that offers symmetric keys. Use a Nextcloud **app password**, which you can revoke, and never your account password. |
| Notes and attachments | `files/notes` | Plain Markdown, ink and image files, as they appear on Nextcloud. |
| Sync state | `files/sync-state.tsv` | Versions of notes, not their content. |
| ChatGPT session | `files/chatgpt-session.json` | Access, refresh and ID tokens. Signing out revokes the refresh token and deletes the file. |
| Imported Nextcloud CA certificate | `files/nextcloud-ca.pem` | A public certificate. |
| Dictation settings | `files/stt.json` | The server address, its token and its self-signed certificate. |
| A recording while it is sent | `cache/dictation.wav` | Deleted after sending or cancelling. |
| The selection sent to the AI | `cache/ai-selection.png` | Overwritten by the next selection. |

The app never writes tokens, passwords or note content to logcat. Log lines hold counts, HTTP status codes and
the name of the import step that failed.

## Trust and network decisions

- **TLS.** The app uses Conscrypt with TLS 1.2 and ECDHE suites with AEAD ciphers only. Each client trusts its own
  certificates and no others (see [TLS and certificate trust](docs/design/tls.md)).
  - Nextcloud: only the CA that you import, or Android's system certificates if you import none.
  - ChatGPT: the root certificates bundled in the app.
  - Dictation: only the certificate of your dictation server, from pairing.
- **Certificate name.** The optional "certificate name" setting makes the app accept a server whose certificate is
  issued to that name whichever host or address it connects to. The certificate must still chain to a trusted CA. The
  setting exists because Android 4.4 cannot resolve `.local` names and some private CAs issue certificates without
  subject alternative names. Leave it empty for the standard host-name check.
- **Dictation token.** It is a random 256-bit secret shared by the server and the tablet. The server compares it in
  constant time. A leaked token lets someone transcribe audio on your server, and nothing else. To revoke it,
  delete the `token` file on the server, run `server/stt/setup.sh` again and pair the tablet again. The server also
  rejects bodies over 12 MB and never logs what was said.
- **ChatGPT tokens.** They never leave the tablet except to `auth.openai.com` and `api.openai.com`. The sign-in
  script checks the ID token's signature, issuer, audience, nonce and expiry before it hands the session to the
  tablet, and it deletes its temporary copies afterwards.

## Why `AdbImportActivity` is exported

`AdbImportActivity` receives what the scripts push over adb: the ChatGPT session, the dictation settings and the
Nextcloud CA certificate. A script pushes a file to `/data/local/tmp` and starts the activity with `am start`. Because
`am start` is not part of the app, the activity has to be exported.

This is acceptable because:

- the activity reads **only** fixed files in `/data/local/tmp`, and only the adb shell user can write there. An
  app that starts the activity cannot choose or change what it imports;
- the activity has no user interface, takes no input from the intent, and stores what it finds in app-private
  storage;
- the scripts delete the pushed files once the import is confirmed, so nothing waits there to be imported;
- anyone who can push files with adb already has the access that adb gives, which is more than this activity.

The remaining risk is that another app on the tablet starts the activity while a script has a file waiting. That
app can only cause the import that the script was about to start.

If you find a way to make the activity import data that adb did not push, report it as a vulnerability.

## Keep secrets out of your reports

Do not include real tokens, passwords, private keys or session files in a report, an issue or a log. Replace them with
placeholders.
