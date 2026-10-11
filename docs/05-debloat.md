# Fase 5: debloat y modo de una sola app

## Resultado (aplicado el 2026-10-11)

- **79 de 80 paquetes ocultos** con `pm block` (grupos A–F). La excepción es `com.google.android.gms`, que
  Android no deja ocultar mientras sea **administrador de dispositivo** (`MdmDeviceAdminReceiver` y
  `auth.managed.admin.DeviceAdminReceiver`).
- **Libreta como pantalla de inicio**, activada desde sus Ajustes y elegida con «Siempre». Inicio lleva a
  Libreta, también desde el editor, y Atrás no sale de ella. TouchWiz sigue instalado como reserva.
- **Reinicio comprobado:** arranca en ~26 s sin errores en logcat y, al desbloquear, aparece Libreta ya
  sincronizada.
- **Memoria tras el reinicio:** 297 MB usados, de los que Play Services aún ocupa ~113 MB en 4 procesos. Sin sus
  servicios compañeros consume más que antes. Para quitarlo del todo hay que retirarle el permiso de
  administrador (Ajustes → Seguridad → Administradores de dispositivos) y ocultarlo después.
- **Revertir:** `scripts/tablet-debloat.sh unblock`. Con `ADB=/c/Users/me/Android/platform-tools/adb.exe` desde
  Git Bash.

## Qué permite Android 4.4 sin root (comprobado en la SM-T113)

| Mecanismo | En la SM-T113 | Reversible |
|---|---|---|
| `pm uninstall --user 0` | **No existe**: `--user` en `uninstall` llegó con Android 5 | — |
| `pm uninstall` de una app de sistema | Imposible sin root (`/system` es de solo lectura) | — |
| `pm disable-user` | **Denegado** al usuario `shell` en esta ROM de Samsung (`SecurityException: attempt to change component state`). Además, el fallo de `pm` lanza un `dumpstate` de ~1 minuto | — |
| **`pm block`** (ocultar, solo en 4.4) | **Funciona** | **Sí**, con `pm unblock` |

Con **`pm block`** no se borra nada de `/system` y se conservan los datos de cada app. Las apps ocultas no
arrancan ni consumen RAM, y tampoco aparecen en Ajustes → Aplicaciones: para recuperarlas hay que usar
`pm unblock` o restaurar de fábrica.

## Memoria antes (dumpsys meminfo)

955 MB de RAM, ~277 MB en uso con la tablet en reposo y Libreta abierta. Los procesos más pesados que se pueden
quitar son: servicios de Google (`gms`, `gms.persistent`, `gapps`, `gms.unstable`), **~77 MB**; Chrome, ~23 MB;
Samsung Apps, ~12 MB; AccuWeather, ~6 MB; Multi-ventana (FlashBar), ~6 MB; Play Música, Búsqueda de Google,
Play Store y Gmail, ~6 MB cada uno; visores Hancom, ~6 MB.

## Grupos

### A. Bloat de Samsung y terceros (recomendado, riesgo bajo)

```
com.hancom.androidpc.appwidget com.hancom.androidpc.hanupdater com.hancom.androidpc.launcher.shared
com.hancom.office.hcell.viewer.hcell_viewer_apk com.hancom.office.hshow.viewer.hshow_viewer_apk
com.hancom.office.hword.viewer.hword_apk
com.sec.android.app.samsungapps com.sec.android.app.billing
com.sec.android.widgetapp.at.hero.accuweather com.sec.android.widgetapp.dualclockdigital
com.sec.android.widgetapp.SPlannerAppWidget com.sec.android.widgetapp.webmanual
com.sec.android.app.FlashBarService com.sec.android.Kies com.wssnps
com.sec.android.allshare.service.fileshare com.sec.android.app.FileShareClient com.sec.android.app.FileShareServer
com.samsung.android.app.memo com.sec.android.app.sysscope com.sec.android.diagmonagent com.samsung.safetyinformation
```

### B. Servicios de Samsung: actualizaciones, cuenta, «Buscar mi móvil» y Knox (riesgo bajo-medio)

La tablet no recibe actualizaciones desde 2017. Se pierden la cuenta Samsung y la localización remota.

```
com.LocalFota com.sec.android.fotaclient com.wssyncmldm
com.osp.app.signin com.sec.pcw.device com.fmm.dm com.fmm.ds com.sec.dsm.system
com.samsung.klmsagent com.sec.esdk.elm com.sec.enterprise.mdm.services.simpin com.sec.enterprise.mdm.vpn
```

### C. Apps de Google (riesgo bajo)

Libreta no usa ninguna. Las 6 que ya estaban desactivadas (Books, Docs, Magazines, Maps, Google+, Hangouts) no
se tocan.

```
com.google.android.music com.google.android.youtube com.google.android.videos com.google.android.play.games
com.google.android.street com.google.android.gm com.google.android.googlequicksearchbox com.android.chrome
```

### D. Servicios de Google y Play Store (mayor ahorro de RAM, riesgo medio)

Libreta no depende de Google Play Services: el TLS viene de Conscrypt, dentro de la APK. Se pierden la cuenta
de Google, su sincronización, Play Store y las notificaciones push de Google.

```
com.google.android.gms com.google.android.gsf com.google.android.gsf.login com.android.vending
com.google.android.backuptransport com.google.android.configupdater com.google.android.feedback
com.google.android.onetimeinitializer com.google.android.partnersetup
com.google.android.syncadapters.calendar com.google.android.syncadapters.contacts com.google.android.tts
```

### E. Multimedia y utilidades (opcional, al gusto)

```
com.sec.android.app.camera com.sec.android.gallery3d com.sec.android.app.videoplayer com.samsung.video
com.sec.android.app.soundalive com.sec.android.mmapp com.sec.android.app.popupcalculator
com.android.email com.android.exchange com.android.calendar
```

### F. Resto no esencial (añadido al pedir «todo menos lo esencial»)

```
com.android.contacts com.android.dreams.basic com.android.dreams.phototable com.android.wallpaper.livepicker
com.android.printspooler com.android.stk com.android.htmlviewer com.google.android.marvin.talkback
com.samsung.android.sdk.spenv10 com.sec.android.app.myfiles com.sec.android.app.clockpackage
com.sec.android.app.chromecustomizations com.sec.android.emergencymode.service com.sec.android.pagebuddynotisvc
com.sec.android.app.ringtoneBR com.sec.android.preloadinstaller
```

Excluidos a propósito por riesgo o por función dudosa: asistentes de configuración (`setupwizard`,
`SecSetupWizard`), apps de fábrica y de servicio, selector de fondos del lanzador de reserva, `asksmanager`
(seguridad de instalación de Samsung), `incallui`, `Preconfig` / `csc`, y `aasaservice`, `accesscontrol`,
`bcservice` y `mt`, cuya función no está clara.

### No se tocan

Todo lo que hace funcionar la tablet o la propia Libreta: `android`, `com.android.systemui`, `com.android.settings`,
`com.android.keyguard`, `com.android.phone` (aunque la tablet sea solo Wi-Fi, el sistema lo necesita), los
`providers`, `packageinstaller`, `defcontainer`, `shell`, **`com.sec.android.inputmethod`** (el teclado),
**`com.sec.android.app.launcher`** (escritorio de reserva), **`com.samsung.android.MtpApplication`** (USB),
`documentsui`, `externalstorage`, `certinstaller`, `keychain`, Bluetooth, Wi-Fi, reloj y alarmas, el gestor de
archivos y las apps de fábrica y de servicio (`factory`, `RilServiceModeApp`, `hwmoduletest`, etc.).

## Aplicar y revertir

```bash
export ADB=/c/Users/me/Android/platform-tools/adb.exe
scripts/tablet-debloat.sh block      # ocultar los grupos A-F
scripts/tablet-debloat.sh unblock    # volver a mostrarlos
```

Cada llamada a `adb shell` lleva `< /dev/null`: si no, `adb` se come la entrada estándar del bucle.

## Modo de una sola app

Android 4.4 no tiene *screen pinning* ni `dpm set-device-owner`; los dos llegaron con Android 5. En su lugar,
**Libreta es la pantalla de inicio**:

- Un `activity-alias` (`.ui.HomeAlias`) con la categoría `HOME`, desactivado por defecto.
- En Libreta → Ajustes, la casilla «Usar Libreta como pantalla de inicio» lo activa
  (`PackageManager.setComponentEnabledSetting`) y abre el selector de inicio, donde se elige Libreta y
  «Siempre». Al desmarcarla se desactiva el alias y Android vuelve a TouchWiz.
- La lista es `singleTask`: Inicio la trae al frente y cierra el editor, que guarda al salir. Atrás no hace
  nada cuando la lista es el inicio.
- El botón «Ajustes de Android» en Libreta → Ajustes da acceso al sistema (Wi-Fi, etc.); la barra de
  notificaciones sigue disponible.
- Al apagar la pantalla, la actividad se detiene, así que también se sincroniza al apagarla y al encenderla.
