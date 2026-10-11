# Fase 0 — Diagnóstico (2026-10-11)

## Tablet

| Dato | Valor |
|---|---|
| Modelo | Samsung Galaxy Tab 3 Lite **SM-T113** (`goyavewifi`, solo Wi-Fi) |
| Android | **4.4.4 KitKat, API 19**, build `KTU84P.T113XXS0AQC2`, parche de seguridad 2017-03-01 |
| Runtime | **Dalvik** (`libdvm.so`), sin ART |
| Chipset | Spreadtrum **SC7730S** (plataforma `sc8830`), 4× Cortex-A7 (`0xc07`) con hotplug, NEON/VFPv4 |
| ABI | **armeabi-v7a** (secundaria `armeabi`) |
| RAM | 955 MB, **`ro.config.low_ram=true`**, heap por app 64 MB (`largeHeap` 128 MB) |
| Almacenamiento | `/data` 5,3 GB (4,7 GB libres), `/system` 1,4 GB |
| Pantalla | 600×1024 px, **160 dpi (mdpi)**, 7" |
| Build | `user`, `release-keys`, `ro.debuggable=0` (sin root) |
| Red | Wi-Fi `192.0.2.20/24`, DNS `192.0.2.10` |
| GMS | Google Play Services 21.33.14, Play Store 5.1.11, Chrome 81 |
| Paquetes | 155 de sistema, 0 de terceros; 8 ya desactivados (Books, Docs, Magazines, Maps, Google+, Hangouts, Hancom launcher, Emergency launcher) |

`nextcloud.local` **no resuelve** desde la tablet (KitKat no tiene mDNS y el DNS local no lo conoce): la app debe usar la IP.

## Nextcloud (`192.0.2.10`)

| Dato | Valor |
|---|---|
| Versión | Nextcloud **34.0.3** sobre Apache; el puerto 80 redirige a 443 |
| Protocolos | TLS 1.0/1.1 **rechazados**; TLS 1.2 y 1.3 aceptados |
| Suites TLS 1.2 | Solo AEAD: `ECDHE-RSA-AES128-GCM-SHA256`, `ECDHE-RSA-AES256-GCM-SHA384`, `DHE-RSA-AES256-GCM-SHA384`, `ECDHE-RSA-CHACHA20-POLY1305` |
| Certificado del servidor | `CN=nextcloud.local`, RSA 2048, SHA-256, **sin SAN**, válido hasta **2026-12-28** |
| CA | `CN=Home CA` (propia y autofirmada), RSA 4096, válida hasta 2035-12-26 → [`certs/my-local-ca.pem`](../certs/my-local-ca.pem) |
| Huella SHA-256 de la CA | `(fingerprint removed)` |

## Problemas que cambian el plan

1. **Suites de cifrado.** El TLS del sistema en API 19 solo ofrece suites ECDHE con CBC-SHA en TLS 1.2; las GCM y ChaCha20 llegan con API 20. El servidor solo acepta suites AEAD, así que **con el TLS del sistema no hay ninguna suite en común**. Forzar TLS 1.2 en OkHttp no basta.
   → Empaquetar **Conscrypt** (`conscrypt-android` 2.5.x, `minSdkVersion 9`, incluye `armeabi-v7a`) y registrarlo como proveedor de seguridad prioritario. Es independiente de Google Play Services y de las CAs del sistema.
2. **Verificación del nombre del servidor.** La app conectará por IP, el certificado no tiene SAN y OkHttp 3.12 no acepta un certificado solo por su CN.
   → Opción preferida: reemitir el certificado del servidor con `subjectAltName = DNS:nextcloud.local, IP:192.0.2.10`. Hay que renovarlo de todas formas antes del 2026-12-28, y la CA embebida no cambia. **Requiere confirmación del usuario.**
   → Alternativa sin tocar el servidor: un `HostnameVerifier` que fije el certificado del servidor, validado contra la CA embebida.
3. **Dalvik y límite de 64K métodos.** Medido en la Fase 1: el APK debug sin minificar tiene **13.015** referencias a métodos en un único `classes.dex`. Con OkHttp, Okio y Conscrypt seguirá muy por debajo de 64K → **no hace falta multidex**; R8 solo en release.
4. **Memoria baja.** Con 64 MB de heap y `low_ram`: lista de notas paginada o con solo los metadatos, sin cargar los cuerpos de las notas en la lista.

## Entorno de desarrollo

- El shell de Claude Code es Git Bash (MSYS2) en Windows; el build se hace en **WSL Ubuntu 26.04**.
- `adb.exe` de Windows en `C:\Users\me\Android\platform-tools` (platform-tools 37.0.1); WSL2 no tiene acceso USB sin `usbipd`.
- En WSL no hay JDK ni Android SDK; se instalan en el `$HOME` sin `sudo` en la Fase 1.
