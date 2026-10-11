# Fase 4: prueba de extremo a extremo (2026-10-11)

La app (build debug) instalada por `adb` en la SM-T113 real, contra el Nextcloud 34.0.3 real. Para verificar el
servidor se usó WebDAV y la API de la app Notes desde WSL, con TLS verificado contra la CA embebida y el nombre real
del certificado. Todo lo de prueba fue en `Notas/Libreta-pruebas/`, que se borró al terminar.

## Hallazgo: la carpeta de notas es `Notas`, no `Notes`

La app Notes de Nextcloud está configurada con `notesPath: "Notas"`; `Notes/` no existe (404). Con el valor por
defecto, la sincronización se abortaba sin tocar nada, que es lo previsto. Desde entonces, «Probar conexión» lee
`/index.php/apps/notes/api/v1/settings` y rellena la carpeta que usa la app Notes (`NotesAppSettings`).

## Resultados

Cada fila es una sincronización, según la línea de log `Libreta` que la app escribe en logcat (solo recuentos).

| Paso | Qué se hizo | Resultado |
|---|---|---|
| Ajustes | URL por IP + `nextcloud.local` como nombre de certificado, «Probar conexión» | «Conexión correcta… Carpeta de la app Notes: «Notas»» |
| 1ª sincronización | Al guardar los ajustes | `down=1` (la nota existente), 197 ms |
| Tablet → Nextcloud | Nota nueva en la categoría nueva `Libreta-pruebas` | `up=1`; en el servidor con el mismo contenido; la **API de Notes** la ve con `category='Libreta-pruebas'` y su título |
| Sin cambios | Sincronizar otra vez | `up=0 down=0`: el ETag del `PUT` coincide con el del `PROPFIND` |
| Nextcloud → tablet | Editar esa nota y crear otra en el servidor | `down=2`; la tablet muestra la edición y la nota nueva en su categoría |
| Conflicto | Editar la misma nota en la tablet y en el servidor | `conflicts=1`; el original conserva el texto del servidor y `… (conflicto 2026-10-11).md` el de la tablet, en ambos lados |
| Borrados | Borrar uno en la tablet y otro en el servidor; cerrar la app con HOME | Sincronización al cerrar: `delLocal=1 delRemote=1` |
| Abrir la app | Volver a abrirla | Sincronización al abrir: sin cambios |
| Subcategoría | Mover la nota a `Libreta-pruebas/Subcarpeta` desde el editor | `up=1 delRemote=1` (MKCOL + PUT + DELETE); la API de Notes la ve con `category='Libreta-pruebas/Subcarpeta'` |
| Limpieza | Borrar la nota de prueba desde la tablet y las carpetas de prueba en el servidor | `delRemote=1`; el servidor vuelve a tener solo la nota original |

Las sincronizaciones tardaron entre 129 y 729 ms. No hubo fallos ni excepciones en logcat.

## Build de release

R8 fallaba por dos clases de plataforma que Conscrypt referencia y que no están en el SDK
(`com.android.org.conscrypt.SSLParametersImpl` y `org.apache.harmony.xnet.provider.jsse.SSLParametersImpl`). Se
corrigió con `-dontwarn` en `proguard-rules.pro`. El APK release ocupa **964 KB** con 5.271 métodos, frente a
3,5 MB y 19.577 del debug. Falta firmarlo e instalarlo.

## Observaciones (no bloquean)

- Las notas descargadas toman como fecha la de la descarga, no la de modificación en el servidor, así que la
  lista las ordena como si fueran recientes.
- Al filtrar por una categoría no aparecen las notas de sus subcategorías.
- Al mover la última nota de una carpeta, la carpeta vacía se queda en el servidor. La app Notes no la muestra.
