# Libreta

Libreta de notas Markdown para una Samsung Galaxy Tab 3 Lite (SM-T113, Android 4.4.4, API 19). Funciona sin
conexión y sincroniza con la carpeta de notas de Nextcloud por WebDAV, así que las notas también aparecen en la
app Notes de Nextcloud. Además de notas de texto tiene notas a mano, para escribir o dibujar con el dedo o con un
lápiz.

El diagnóstico de la tablet y del servidor está en [`docs/00-diagnostico.md`](docs/00-diagnostico.md).

## Módulos

| Módulo | Tipo | Contenido |
|---|---|---|
| `:core` | Kotlin/JVM, sin Android | Dominio (`NotePath`, `NoteRepository`, `NoteService`) y motor de sincronización. Se prueba en la JVM. |
| `:app` | Aplicación Android (minSdk 19) | Raíz de composición (`AppContainer`), adaptadores (`data.local`: ficheros `.md` y estado de sincronización; `data.remote`: WebDAV y TLS; `data.settings`), orquestación de la sincronización (`sync`) y UI con Views clásicas (`ui`). |

Los adaptadores van en `:app` y no en módulos JVM aparte: son Java puro, así que sus tests corren en la JVM
(`testDebugUnitTest`), y al estar en `:app` Android lint comprueba que no usen APIs posteriores a API 19.

**Almacenamiento local:** `files/notes/` en el almacenamiento interno de la app, con una subcarpeta por
categoría, igual que en `Notes/` de Nextcloud. Cada escritura va a un fichero oculto que luego se renombra sobre
el definitivo, así que un corte nunca deja una nota a medias.

**Editor:** guarda solo al dejar de estar visible (`onPause`), sin botón de guardar, como Nextcloud Notes. El
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
páginas y papel pautado, cuadriculado o en blanco. Se guarda al salir y 3 s después de dejar de tocar la
pantalla. En Nextcloud cada nota es un `.md` con una imagen por página, más los trazos en
`.attachments.<id>/libreta.ink.json`, así que Notes muestra las páginas y la tablet puede seguir editándolas.
Los adjuntos acompañan a su nota al cambiarla de categoría o borrarla. Formato, detalles y calibración del
lápiz: [`docs/06-escritura-a-mano.md`](docs/06-escritura-a-mano.md).

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
| Animal Sniffer | 2.0.1 + firma Gummy Bears `gummy-bears-api-19` | Hace fallar el build si `:core` usa una API del JDK que no existe en API 19; admite los métodos que D8 reescribe, como `Integer.hashCode` |

Sin AndroidX, sin AppCompat y sin Compose: solo `android.app.Activity` y el tema Holo del sistema.

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
