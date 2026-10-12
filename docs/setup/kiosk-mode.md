# Kiosk mode

This page explains how to turn the tablet into a single-purpose notebook: you hide the system apps you don't
need, and you make Libreta the home screen. Both steps are optional and independent, and you can undo both.

The lists of apps in this page are for a Samsung Galaxy Tab 3 Lite (SM-T113). On another device, the script
reports the packages that don't exist and skips them.

## What Android 4.4 allows without root

Android 4.4 has neither screen pinning nor device-owner mode, which arrived with Android 5. Without root, there
are two ways to reduce the system to what you need:

| Mechanism | Works on Android 4.4 | Notes |
|---|---|---|
| `pm uninstall --user 0` | No | The `--user` option for `uninstall` arrived with Android 5. |
| `pm uninstall` of a system app | No | `/system` is read-only without root. |
| `pm disable-user` | No | Samsung's ROM denies it to the `shell` user (`SecurityException`). A failed `pm` call also starts a `dumpstate` that takes about a minute. |
| `pm block` | Yes | Hides a package. It is reversible with `pm unblock`. |

Libreta uses `pm block`. A blocked package stays in `/system` and keeps its data, but it doesn't start, uses no
memory, and doesn't appear anywhere, not even in Android's app settings. To get a package back, run
`pm unblock` (the script does this) or reset the tablet to factory settings.

## Hide the apps you don't need

The script `scripts/tablet-debloat.sh` hides or restores groups of packages. Connect the tablet with USB
debugging enabled, then run:

```bash
scripts/tablet-debloat.sh block              # hide groups A-F
scripts/tablet-debloat.sh block A B C        # hide only groups A, B and C
scripts/tablet-debloat.sh unblock            # restore everything
scripts/tablet-debloat.sh unblock D          # restore only group D
```

The script prints one line for each package that it can't change, and a summary such as `block: 79 ok, 1 failed`.
In WSL, set `ADB` to the Windows `adb.exe`. See [Build and install](build.md#use-wsl). The exact package names
are in the `GROUP_A` to `GROUP_F` arrays at the top of the script. For the other options, see
[Scripts](../reference/scripts.md#tablet-debloatsh).

### Groups

Start with the low-risk groups, restart the tablet, and add more only if you need the memory.

| Group | Contents | What you lose | Risk |
|---|---|---|---|
| A | Samsung and third-party apps: Hancom Office viewers, Samsung Apps, widgets, Kies, file sharing, S Memo, diagnostics | The apps themselves | Low |
| B | Samsung services: firmware updates, Samsung account, Find My Mobile, Knox management | Samsung account sign-in and remote location. The tablet gets no firmware updates, but the last one was released years ago. | Low to medium |
| C | Google apps: Music, YouTube, Videos, Play Games, Street View, Gmail, Google Search, Chrome | The apps. Libreta uses none of them. | Low |
| D | Google services and Play Store: Google Play Services, the services framework, Play Store, sync adapters, text-to-speech | Your Google account, Google sync, Play Store and Google push notifications. Libreta doesn't depend on Play Services, because its TLS stack ships in the APK. | Medium |
| E | Media and utilities: camera, gallery, video player, sound, calculator, email, calendar | The apps. Skip this group if you use them. | Low |
| F | The rest of the non-essential apps: contacts, screen savers, live wallpaper picker, print spooler, SIM toolkit, HTML viewer, TalkBack, S Pen SDK, file manager, clock, ringtone backup | The apps. Skip this group if you use the clock or the file manager. | Low |

Group D saves the most memory: on the SM-T113 it removes about 77 MB of processes. One package in it,
`com.google.android.gms`, can't be hidden while it is a device administrator. The script reports it as failed.
To hide it, first remove its administrator permission in Android's «Ajustes» > «Seguridad» >
«Administradores de dispositivos» (Settings > Security > Device administrators). Without its companion services,
Play Services can use more memory than before, so hide it fully or leave group D alone.

### Packages the script never touches

The script leaves alone everything that the tablet or Libreta needs: `android`, the system UI, the Settings app,
the keyguard, the phone package (the system needs it even on a Wi-Fi-only tablet), the content providers, the
package installer, the keyboard, the fallback launcher, USB (MTP), the documents UI and external storage, the
certificate installer and key chain, Bluetooth, Wi-Fi, and the factory and service apps. It also leaves alone
the setup wizards, the Samsung install security manager, the in-call UI and a few packages whose purpose is
unclear.

## Use Libreta as the home screen

With Libreta as the home screen, the Home button always opens Libreta, and the Back button doesn't leave it.

1. On the tablet, open Libreta, then «Ajustes» (Settings).
2. In the «Tableta» (Tablet) section, turn on «Usar Libreta como pantalla de inicio» (Use Libreta as the home
   screen).
3. In the dialog that Android shows, select «Libreta» and then «Siempre» (Always).

To go back, turn the option off. Libreta disables its home entry, and Android returns to the Samsung home
screen. The Samsung launcher stays installed as a fallback, so don't hide it.

### How it works

Libreta declares an `activity-alias` (`.ui.HomeAlias`) with the `HOME` category. The alias is disabled by default,
and the setting enables it with `PackageManager.setComponentEnabledSetting`. The note list uses the `singleTask`
launch mode, so the Home button brings it to the front and closes the editor, which saves on exit.

When the screen turns off, the activity stops, so Libreta also syncs when the screen goes off and on.

### Reach Android's settings

Use the «Ajustes de Android» (Android settings) button in Libreta's settings to open the system settings, for
example to change the Wi-Fi network. The notification bar stays available.

## Undo everything

1. Turn off «Usar Libreta como pantalla de inicio».
2. Run `scripts/tablet-debloat.sh unblock`.
