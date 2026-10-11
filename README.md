# Libreta

Libreta de notas Markdown para una Samsung Galaxy Tab 3 Lite (SM-T113, Android 4.4.4, API 19). Funciona sin
conexión y sincroniza con la carpeta de notas de Nextcloud por WebDAV, así que las notas también aparecen en la
app Notes de Nextcloud. Además de notas de texto tiene notas a mano, para escribir o dibujar con el dedo o con un
lápiz, y sobre lo escrito a mano puede pasar a texto o conversar con la IA usando la suscripción de ChatGPT.

El diagnóstico de la tablet y del servidor está en [`docs/00-diagnostico.md`](docs/00-diagnostico.md). Lo que viene
después, por fases: [`docs/roadmap.md`](docs/roadmap.md).

## Módulos

| Módulo | Tipo | Contenido |
|---|---|---|
| `:core` | Kotlin/JVM, sin Android | Dominio (`NotePath`, `NoteRepository`, `NoteService`) y motor de sincronización. Se prueba en la JVM. |
| `:app` | Aplicación Android (minSdk 19) | Raíz de composición (`AppContainer`), adaptadores (`data.local`: ficheros `.md` y estado de sincronización; `data.remote`: WebDAV y TLS; `data.ai`: sesión de ChatGPT y Responses API; `data.stt`: grabación WAV y servicio de dictado; `data.settings`), orquestación de la sincronización (`sync`) y UI con Views clásicas (`ui`). |

Los adaptadores van en `:app` y no en módulos JVM aparte: son Java puro, así que sus tests corren en la JVM
(`testDebugUnitTest`), y al estar en `:app` Android lint comprueba que no usen APIs posteriores a API 19.

**Almacenamiento local:** `files/notes/` en el almacenamiento interno de la app, con una subcarpeta por
categoría, igual que en `Notes/` de Nextcloud. Cada escritura va a un fichero oculto que luego se renombra sobre
el definitivo, así que un corte nunca deja una nota a medias.

**Editor:** la nota se abre formateada (Markdown con sus imágenes) y «Editar» pasa al texto, con la sintaxis
resaltada; ver [`docs/07-ia.md`](docs/07-ia.md#markdown). Guarda solo al dejar de estar visible (`onPause`) o al
volver a la vista, sin botón de guardar, como Nextcloud Notes. El
nombre del fichero sigue a la primera línea. Si la nota cambió en disco mientras se editaba (por ejemplo, porque
una sincronización trajo una versión nueva), el texto del editor se guarda como copia de conflicto y no pisa
nada.

## Arquitectura de la sincronización

```
            NoteRepository  (puerto: list / read / write / delete con WritePrecondition)
              ▲          ▲
   almacén local       SyncStrategy (lado remoto, un protocolo)
   (ficheros .md)         ▲
                     NextcloudWebDavSync   (después, opcionalmente, la Notes API)

 SyncEngine ── usa ──▶ SyncPlanner (función pura, comparación a tres bandas)
     │                    local × remoto × SyncBase (estado de la última sincronización)
     └── persiste ──▶ SyncStateStore
```

- **Versiones opacas:** un hash del contenido en local y el ETag en remoto. Una nota ha cambiado si su versión
  es distinta de la guardada en `SyncBase`.
- **Concurrencia optimista:** las escrituras llevan `WritePrecondition` (`If-Match` / `If-None-Match: *`).
  Si una escritura pierde una carrera, no se fuerza: se resuelve como conflicto o se reintenta en la siguiente
  sincronización.
- **Conflictos:** si cambiaron los dos lados, el texto remoto conserva el nombre original y el local se guarda
  como `Nombre (conflicto AAAA-MM-DD).md` en ambos lados. Si los dos textos son iguales, no hay conflicto.
- **Edición frente a borrado:** gana la edición, así que sincronizar nunca hace perder texto.
- **Fallos:** si no hay red, la sincronización se detiene sin tocar nada. El progreso se guarda tras cada paso,
  de modo que una sincronización interrumpida continúa donde lo dejó.

### WebDAV con Nextcloud (`NextcloudWebDavSync`)

- **Listado:** `PROPFIND` con `Depth: 1` carpeta a carpeta (Nextcloud puede rechazar `Depth: infinity`). Se
  sincronizan las notas (`.md`, `.txt`) y los adjuntos de las carpetas `.attachments.*` de Notes; las demás
  carpetas ocultas y los otros ficheros se ignoran.
- **Versiones:** ETag de `getetag` y de las cabeceras `ETag` de `GET` y `PUT`, con `PROPFIND` de respaldo si el
  servidor no la envía.
- **Escrituras:** `PUT` con `If-None-Match: *` (crear) o `If-Match` (actualizar); `DELETE` con `If-Match`. Un 412
  se convierte en conflicto. Las categorías nuevas se crean con `MKCOL`. Un 423 (nota abierta en el editor web
  de Nextcloud) deja esa nota para la siguiente sincronización sin parar las demás.
- **Carpeta de notas ausente:** si falta (404), la sincronización se aborta. Nunca se interpreta como «se
  borraron todas las notas», que vaciaría la tablet.

### TLS en KitKat

- **Conscrypt 2.5.3** se pasa como proveedor del `SSLContext`, no se instala de forma global. Aporta TLS 1.2 con
  AES-GCM y ChaCha20, que el TLS de Android 4.4 no tiene y el servidor exige.
- **`ConnectionSpec`:** solo TLS 1.2 y solo suites ECDHE con AEAD.
- **Confianza:** solo en la CA embebida (`res/raw/nextcloud_ca.pem`); las CAs del sistema no se usan.
- **Nombre del servidor:** la tablet conecta por IP y el certificado solo lleva `CN=nextcloud.local`, sin
  SAN. `CertificateNameVerifier` acepta el certificado si está emitido a ese nombre (configurable en Ajustes).
  Es seguro porque la única raíz de confianza es la CA propia.
- **Verificado en la tablet:** con el nombre configurado, la conexión se acepta; sin él, se rechaza. En la JVM,
  `TlsTest` reproduce el caso con certificados equivalentes y además comprueba que se rechazan un nombre
  distinto y otra CA.

### Cuándo se sincroniza

Al abrir la app y al pasarla a segundo plano (`ActivityLifecycleCallbacks`; las rotaciones no cuentan), con el
botón ⟳ y al guardar los ajustes. Se ejecuta en un `IntentService`, que agrupa las peticiones acumuladas, porque
WorkManager pide API 21.

### Credenciales

Usuario y contraseña de aplicación de Nextcloud se configuran en Ajustes y se guardan en las
`SharedPreferences` privadas de la app (`allowBackup=false`). La contraseña no se muestra nunca; dejar el
campo vacío la conserva. Si cambian el servidor, el usuario o la carpeta, se borra el estado de sincronización.

### Categorías

Son subcarpetas, como en Nextcloud Notes, y admiten subcategorías con `/`. En la lista se filtran con el
desplegable de la barra superior; en el editor, el botón «Categoría» mueve la nota, y el cambio se aplica al
guardar.

### Notas a mano

Lienzo con pluma (panel de colores, tres grosores), borrador por zona o por trazo entero, deshacer, varias
páginas y papel pautado, cuadriculado o en blanco. Zoom de 1× a 4× con dos dedos o con los controles sobre la
página ([`docs/09-zoom.md`](docs/09-zoom.md)). Gira con la tablet; en horizontal, la página vertical se ajusta al
ancho y se pueden añadir páginas apaisadas ([`docs/10-orientacion.md`](docs/10-orientacion.md)). Se guarda al salir y 3 s después de dejar de tocar la
pantalla. En Nextcloud cada nota es un `.md` con una imagen por página, más los trazos en
`.attachments.<id>/libreta.ink.json`, así que Notes muestra las páginas y la tablet puede seguir editándolas.
Los adjuntos acompañan a su nota al cambiarla de categoría o borrarla. Formato, detalles y calibración del
lápiz: [`docs/06-escritura-a-mano.md`](docs/06-escritura-a-mano.md).

### IA

En una nota a mano, la herramienta **Seleccionar** marca un recuadro alrededor de los trazos para moverlos,
cambiarles el tamaño, el color o el grosor, copiarlos, pegarlos o borrarlos
([`docs/08-seleccion.md`](docs/08-seleccion.md)), y ofrece **Convertir a texto** (revisar y
añadir a la nota) y **Conversar con IA** (chat sobre el recorte, que se puede guardar como nota). Los modelos
son `gpt-6-luna` para convertir y `gpt-6.1-sol` para conversar, con razonamiento medio, pagados con la suscripción de ChatGPT del usuario mediante el flujo oficial
«Sign in with ChatGPT» para apps de código abierto; la tablet habla directamente con `api.openai.com`. La sesión
se inicia en el navegador del PC con `scripts/chatgpt-login.sh`, que la pasa a la tablet por adb. Diseño, tokens,
TLS y pruebas: [`docs/07-ia.md`](docs/07-ia.md).

**Dictado:** un micrófono en el editor, en la nota a mano y en el chat graba hasta 5 minutos y lo transcribe
faster-whisper (`large-v3-turbo`) en el servidor, en un contenedor (`server/stt`, desplegado en
`/srv/docker/libreta/stt`) con HTTPS y token propios. `scripts/stt-pair.sh` empareja la tablet.

### Libreta como pantalla de inicio

Desde Libreta → Ajustes → «Usar Libreta como pantalla de inicio» se activa el modo de una sola app: un
`activity-alias` con la categoría `HOME`, con TouchWiz de reserva. Es lo más parecido a un modo kiosco que permite
Android 4.4 sin root. Las apps del sistema que sobran están ocultas con `pm block`; la lista, los motivos y cómo
revertirlo están en [`docs/05-debloat.md`](docs/05-debloat.md).

## Toolchain y compatibilidad con API 19

| Pieza | Versión | Nota |
|---|---|---|
| Gradle | 9.8.0 | wrapper con SHA-256 verificado |
| Android Gradle Plugin | 9.4.1 | No fija ningún mínimo de minSdk; verificado instalando en la tablet |
| Kotlin | 2.4.20 | El stdlib funciona en Dalvik/API 19, verificado en la tablet |
| JDK | 21 (bytecode 11) | AGP pide JDK 17 o superior; D8 transforma el bytecode para API 19 |
| compileSdk / targetSdk | 36 | minSdk 19 |
| OkHttp | 3.12.13 | última rama compatible con API < 21 |
| Conscrypt | 2.5.3 | la 2.6 y posteriores piden minSdk 21 |
| Markwon | 4.6.2 | Markdown con spans nativos; minSdk 16, parser commonmark-java 0.13.0 en bytecode Java 7 |
| Animal Sniffer | 2.0.1 + firma Gummy Bears `gummy-bears-api-19` | Hace fallar el build si `:core` usa una API del JDK que no existe en API 19; admite los métodos que D8 reescribe, como `Integer.hashCode` |

Sin AndroidX, sin AppCompat y sin Compose: solo `android.app.Activity` y el tema Holo del sistema. La única pieza
de AndroidX es `androidx.annotation`, que llega con Markwon y solo contiene anotaciones.

**Guardia de API 19 en `:core`.** Android lint no aplica `NewApi` a módulos JVM, así que no protege `:core`.
Animal Sniffer revisa el bytecode de `:core` contra la firma de API 19 y detecta tanto clases (`java.time`)
como los métodos de JDK 8 que Kotlin expone en sus colecciones (`Map.getOrDefault`), que ni siquiera compilando
contra el `android.jar` se detectan. El `jar` de `:core` depende de esta comprobación, así que se ejecuta en
cualquier build de la app.

## Compilar e instalar

El build se ejecuta en WSL (Ubuntu), con el JDK y el Android SDK en el `$HOME`. La tablet se ve con el `adb.exe`
de Windows, porque WSL2 no tiene acceso al USB.

```bash
# WSL
export JAVA_HOME=$HOME/opt/jdk-21 ANDROID_HOME=$HOME/Android/Sdk
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

```bash
# Windows (Git Bash)
/c/Users/me/Android/platform-tools/adb.exe install -r app/build/outputs/apk/debug/app-debug.apk
```

La contraseña de aplicación de Nextcloud se introduce en la pantalla de ajustes de la app. Nunca va en el
repositorio.
