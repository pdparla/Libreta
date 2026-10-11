# Libreta

Libreta de notas Markdown para una Samsung Galaxy Tab 3 Lite (SM-T113, Android 4.4.4, API 19). Funciona sin
conexión y sincroniza con la carpeta `Notes/` de Nextcloud por WebDAV, así que las notas también aparecen en la
app Notes de Nextcloud.

El diagnóstico de la tablet y del servidor está en [`docs/00-diagnostico.md`](docs/00-diagnostico.md).

## Módulos

| Módulo | Tipo | Contenido |
|---|---|---|
| `:core` | Kotlin/JVM, sin Android | Dominio y motor de sincronización. Se prueba en la JVM. |
| `:app` | Aplicación Android (minSdk 19) | UI con Views clásicas, almacenamiento local y conexión TLS. |

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

## Toolchain y compatibilidad con API 19

| Pieza | Versión | Nota |
|---|---|---|
| Gradle | 9.8.0 | wrapper con SHA-256 verificado |
| Android Gradle Plugin | 9.4.1 | No fija ningún mínimo de minSdk; verificado instalando en la tablet |
| Kotlin | 2.4.20 | El stdlib funciona en Dalvik/API 19, verificado en la tablet |
| JDK | 21 (bytecode 11) | AGP pide JDK 17 o superior; D8 transforma el bytecode para API 19 |
| compileSdk / targetSdk | 36 | minSdk 19 |
| OkHttp *(fase 3)* | 3.12.13 | última rama compatible con API < 21 |
| Conscrypt *(fase 3)* | 2.5.3 | la 2.6 y posteriores piden minSdk 21 |
| Animal Sniffer | 2.0.1 + firma `android-api-level-19` | Hace fallar el build si `:core` usa una API del JDK que no existe en API 19 |

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
