# TLS and certificate trust

Libreta talks to three kinds of server: your Nextcloud server, OpenAI, and, optionally, your own dictation service.
Each has its own trust configuration, and none of them uses Android 4.4's TLS stack. This document explains why,
and what each client trusts.

To set up the Nextcloud side, see [Connect to Nextcloud](../setup/nextcloud.md).

## Why Conscrypt

The TLS stack of Android 4.4 (API 19) offers only ECDHE suites with CBC-SHA in TLS 1.2. The AES-GCM and
ChaCha20-Poly1305 suites (the AEAD suites) arrive with API 20. A server that accepts only AEAD suites, which is a
common hardening choice, has no suite in common with the system stack, so no setting of OkHttp can make it
connect.

The app therefore bundles [Conscrypt](https://github.com/google/conscrypt) 2.5.3 (`conscrypt-android`, which
includes `armeabi-v7a`). Conscrypt:

- brings TLS 1.2 with AES-GCM and ChaCha20-Poly1305;
- doesn't depend on Google Play Services;
- is passed explicitly to every `SSLContext` as its provider, and is never installed as the process-wide default,
  so nothing else in the process changes.

Version 2.6 and later need minSdk 21, so 2.5.3 is the ceiling. See
[Compatibility](../reference/compatibility.md#dependency-ceilings).

One Conscrypt provider serves all clients. It's created on first use, off the main thread, because it loads a
native library. If that fails, a sync reports a failure instead of crashing.

## Connection spec

`Tls.CONNECTION_SPEC` is used by every client:

- TLS 1.2 only.
- Only forward-secret AEAD suites: ECDHE with RSA or ECDSA, and AES-128-GCM, AES-256-GCM or ChaCha20-Poly1305.

A server that offers only older protocols, CBC suites or non-ECDHE key exchange can't connect. That is deliberate:
it keeps weak configurations out of the app. If you must support such a server, change the spec in `Tls.kt`.

## Three clients, three trust configurations

All clients derive from one shared base client (see [Architecture](architecture.md#composition-root)). Because
OkHttp keys connections by address including the socket factory, a connection made under one trust configuration
is never reused by another.

| Client | Talks to | Trusts | Host-name check |
|---|---|---|---|
| Nextcloud | Your server | Your imported CA certificates only; if you imported none, Android's system certificates | Standard, or the certificate-name check if you set one |
| OpenAI | `api.openai.com`, `auth.openai.com` | Only the roots in `res/raw/openai_roots.pem` | Standard |
| Dictation | Your dictation service | Only its own certificate, which you pair | Standard |

### Nextcloud

The trust source depends on whether you imported a CA:

- **Imported CA.** `scripts/nextcloud-ca.sh <ca.pem>` stores one or more CA certificates as
  `files/nextcloud-ca.pem` in the app's private storage (see
  [Connect to Nextcloud](../setup/nextcloud.md)). The Nextcloud client then trusts only those certificates. The
  system store isn't consulted. This is the right setup for a server with a private CA.
- **No imported CA.** The client uses Android's system trust store. KitKat's store is old (its last update is
  from 2017) and lacks newer roots such as ISRG Root X1, so a server with a Let's Encrypt certificate fails
  until you import that root with the same script.

The APK doesn't bundle any Nextcloud CA. Settings shows which source is in use: the subject name of the imported
certificate, or "system certificates".

`NextcloudClientFactory` reads the imported PEM each time it builds a client and caches the base client by that
PEM. A new import therefore takes effect on the next client built, with no restart.

The factory builds the trust manager with `Tls.trustManagerFor` for an imported CA, and `Tls.systemTrustManager`
otherwise. Both give an `X509TrustManager` that validates the full chain: signatures, validity dates and
constraints. The app never disables validation.

Redirects are off for this client, and the URL must be HTTPS; see [Sync](sync.md#redirects-are-off).

### The certificate name

Standard host-name verification compares the host you dialled with the names in the certificate. That fails in two
common home setups:

- You connect by IP address, and the certificate doesn't list that address.
- The certificate is issued to a `.local` name. KitKat has no mDNS, so the name doesn't resolve and you can't dial
  it. OkHttp 3.12 also doesn't accept a certificate that has only a common name and no subject alternative names.

The optional **certificate name** setting handles this. When it's set, the client replaces the standard check with
`CertificateNameVerifier`, which accepts the server if its certificate is issued to that name, whatever host or IP
address the app dialled. When it's blank, the standard check applies.

The verifier looks at the first (leaf) certificate of the peer:

1. The DNS names in the subject alternative names.
2. Then the common name of the subject.

It accepts when any of them equals the configured name, ignoring case. It doesn't match wildcards, and it rejects
a session with no certificate.

#### Why this is safe

The verifier replaces only the question "is this certificate for the host I dialled?". The chain is verified
against the trusted roots before the verifier runs, during the handshake. Only a certificate that those roots
issued to the configured name can pass.

- With an imported private CA, the only root is yours. A certificate for your name can only come from your CA, and
  so only from your server. This is the intended setup.
- With the system store, a certificate for the name must come from a public CA. A party can get one only for a
  name it controls. A private name such as `nextcloud.home.arpa` or a `.local` name can't get a public
  certificate at all, so use the imported CA for those.

What you give up is binding the certificate to the address you dial. The trust comes from the CA and the name, not
from the network path. If you dial the wrong IP, the connection still fails unless that host presents a valid
certificate for your name.

`TlsTest` reproduces the case on the JVM with equivalent certificates. It checks that a certificate from the
trusted CA is accepted by its name although the app dials another host, and that three cases are refused: the
standard check on a certificate with no subject alternative names, a certificate from the trusted CA for another
name, and a certificate with the right name from another CA.

### OpenAI

The ChatGPT sign-in and the model calls use a separate client. It trusts only the roots in
`res/raw/openai_roots.pem`, which hold the public roots that `api.openai.com` and `auth.openai.com` chain to: the
Google Trust Services roots GTS Root R1 and R4, and the Let's Encrypt roots ISRG Root X1 and X2. It checks host
names the standard way.

Why a bundled list instead of the system store:

- The system store of a 2017 device lacks some of these roots, so the system store alone can't verify the hosts.
- The bundle is small and holds only what these two hosts need.
- It never includes your Nextcloud CA, and the Nextcloud client never trusts these roots. A bad certificate for one
  service can't be used against the other.

The cost is maintenance. If OpenAI changes the root its hosts chain to, the list in `openai_roots.pem` needs an
update, and the app needs a new release. This client uses the same Conscrypt and connection spec as the others. Its
timeouts are longer, because a model can keep a stream quiet for a while before it answers. See
[AI design](ai.md) for the protocol.

### Dictation service

The dictation service uses a self-signed certificate that `server/stt/setup.sh` generates. The pairing script
(`scripts/stt-pair.sh`) copies that certificate, the service address and a token to the tablet, which stores them
in `files/stt.json`.

The dictation client builds its trust manager from that one certificate, so it trusts nothing else: not the system
store, not your Nextcloud CA. The certificate lists the server's IP address (and optionally a DNS name) in its
subject alternative names, so the standard host-name check passes for the address in the service URL. If you pair
again with a new certificate, the client rebuilds. See [Set up the dictation server](../setup/dictation-server.md).

## Testing TLS on the JVM

The TLS tests use OkHttp's `MockWebServer` and `okhttp-tls` to create certificates. They cover the trust manager
with and without the right CA, the connection spec, and `CertificateNameVerifier`. They run in `testDebugUnitTest`.
See [Testing](../testing.md).
