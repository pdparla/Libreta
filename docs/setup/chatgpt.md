# Sign in with ChatGPT

This page explains how to give Libreta access to your ChatGPT plan, so that the AI features run on your plan
instead of a paid API key. You sign in once, on your computer, and a script copies the session to the tablet.

Libreta uses the official *Sign in with ChatGPT* flow for open-source and personal apps that run locally. For the
details of the flow, see the
[OpenAI documentation](https://developers.openai.com/siwc/token-sharing-open-source). For how the app uses the
session, see [AI](../design/ai.md).

## Why you sign in on a computer

The browser on an old tablet can't complete the OpenAI sign-in page. OpenAI's documentation covers this case: you
sign in on another machine, and you pass the credentials to the device over a secure channel. Here the channel is
a USB cable and `adb`.

## Requirements

- A ChatGPT plan that includes Sign in with ChatGPT usage.
- A computer with Java 11 or later, `adb` and a web browser.
- The tablet connected over USB with USB debugging on. See [Build and install](build.md#prepare-the-tablet).
- Libreta installed on the tablet, with the screen unlocked.
- Port 1455 free on the computer. The browser returns to `http://127.0.0.1:1455` after you sign in.

## Sign in

1. Connect the tablet to your computer.
2. Run the script from the project folder:

   ```bash
   scripts/chatgpt-login.sh
   ```

3. The script opens your browser at the OpenAI sign-in page. Sign in and approve the access for Libreta.
4. The browser returns to a local address and the script continues. If the browser ends on a page that doesn't
   load, copy the full address from the address bar and paste it into the terminal.
5. The script copies the session to the tablet and waits for the app to confirm it.

When it finishes, the script prints a confirmation, and «Ajustes» (Settings) on the tablet shows «IA (ChatGPT)»
with the account that is signed in.

### Use WSL

The browser returns to `127.0.0.1` on the machine that runs Java. In WSL, use the Java and the `adb` of
Windows so that the browser and Java share the same address:

```bash
JAVA=/path/to/jdk-21/bin/java.exe ADB=/path/to/platform-tools/adb.exe scripts/chatgpt-login.sh
```

Set `JAVA` and `ADB` to the `.exe` files of your Windows installation. If you run Java inside WSL, paste the
address from the browser into the terminal as described above.

## What the script does

1. It registers the tablet as a client the first time. It creates a host identifier for the tablet and stores it,
   with the client identifier that OpenAI issues, in `~/.libreta/chatgpt-client.json`. These values aren't
   secrets. Later sign-ins reuse them.
2. It opens the browser at the OpenAI authorization page, with a one-time `state`, `nonce` and PKCE challenge.
   Libreta asks for your basic identity (to show the account name) and for permission to use your plan. Nothing else.
3. It checks the answer: the `state`, the client, and the `id_token` (nonce, issuer, audience, expiry and a
   signature checked against OpenAI's published keys).
4. It writes the session to a temporary file with owner-only permissions, pushes it to the tablet, starts the
   app's import screen, and waits for the confirmation. Then it deletes both copies.

The script never prints the tokens.

## Where the session lives

On the tablet, the session is the file `files/chatgpt-session.json` in Libreta's private storage. The app
doesn't back it up and never writes it to the log.

The app renews the session by itself. The access token lasts one hour. The renewal token lasts 30 days, and each
renewal returns a new one. As long as you use an AI feature at least once a month, the session doesn't expire.

## When the session expires

If the session expires, or you revoke it, the AI features show a message that asks you to sign in again. Run
`scripts/chatgpt-login.sh` again. The expiry happens after 30 days without using the AI, or after you sign out.

## Sign out

1. On the tablet, open «Ajustes».
2. In «IA (ChatGPT)», tap «Cerrar sesión» (Sign out) and confirm.

Libreta revokes the renewal token at OpenAI and deletes the local session. If the app can't confirm the
revocation, for example because there is no network, it still deletes the session and tells you that you can
disconnect Libreta in your ChatGPT settings.

## Troubleshooting

| Problem | What to do |
|---|---|
| The script says that no tablet is connected. | Run `adb devices`. Check the cable and the USB debugging prompt on the tablet. In WSL, set `ADB`. |
| The script says that the tablet didn't respond. | Check that Libreta is installed and that the tablet screen is unlocked. |
| The browser ends on a page that doesn't load. | Copy the full address of that page and paste it into the terminal. |
| An AI feature reports that you reached the usage limit of your plan. | Wait for your plan's limit to reset. |

## Next steps

- [Use the assistant](../guide/assistant.md)
- [Deploy the dictation server](dictation-server.md)
