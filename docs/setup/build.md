# Build and install

This page explains how to build Libreta from source and install it on a tablet. You build on a Linux, macOS,
Windows or WSL machine, and you install over USB with `adb`.

## Requirements

| Tool | Version | Notes |
|---|---|---|
| JDK | 21 | The Android Gradle Plugin needs JDK 17 or later. The build compiles to Java 11 bytecode, and D8 translates it for API 19. |
| Android SDK | Platform 36 and Platform-Tools | Install the command-line tools, then `platform-tools` and `platforms;android-36`. Gradle downloads the build tools it needs if you have accepted the SDK licenses. |
| Gradle | 9.8.0 | You don't install it. The wrapper (`./gradlew`) downloads it and checks its SHA-256 checksum. |
| `adb` | Any recent version | Part of Platform-Tools. |

The project doesn't use AndroidX, AppCompat or Compose, and it has no other build-time dependencies.

## Set up the toolchain

1. Install a JDK 21 and set `JAVA_HOME` to it.
2. Install the Android SDK and set `ANDROID_HOME` to it. Alternatively, create `local.properties` in the project
   root with the line `sdk.dir=/path/to/Android/Sdk`. Git ignores this file.
3. Accept the SDK licenses:

   ```bash
   "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses
   ```

## Build

Run the tests, build the debug APK and run Android lint in one command:

```bash
./gradlew :core:test :app:assembleDebug :app:lintDebug
```

| Task | What it does |
|---|---|
| `:core:test` | Runs the unit tests of the `core` module on the JVM. |
| `:app:testDebugUnitTest` | Runs the unit tests of the `app` module on the JVM. |
| `:app:assembleDebug` | Builds `app/build/outputs/apk/debug/app-debug.apk`. It also runs the API 19 check of the `core` module (see below). |
| `:app:lintDebug` | Runs Android lint. A lint error fails the build, including the use of any API newer than API 19. |
| `:app:assembleRelease` | Builds a minified and shrunk APK with R8 at `app/build/outputs/apk/release/app-release-unsigned.apk`. |

Any build of the app runs Animal Sniffer on the `core` module, which checks its bytecode against the API 19
signature. This catches JDK classes and methods that don't exist on Android 4.4, such as `java.time` or
`Map.getOrDefault`. Android lint can't do this in a JVM module.

## Build a release APK

The build has no signing configuration, so `assembleRelease` produces an unsigned APK. To install it, align and
sign it with the tools from the Android SDK build tools:

1. Create a signing key once. Keep the key file outside the repository. Git ignores `*.jks`, `*.keystore`,
   `keystore.properties` and `secrets.properties`, but a key outside the working tree is safer.

   ```bash
   keytool -genkeypair -keystore ~/libreta.jks -alias libreta -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Align and sign the APK. Replace `<version>` with the build tools version you have installed:

   ```bash
   BT="$ANDROID_HOME/build-tools/<version>"
   "$BT/zipalign" -f -p 4 app/build/outputs/apk/release/app-release-unsigned.apk libreta-aligned.apk
   "$BT/apksigner" sign --ks ~/libreta.jks --out libreta-release.apk libreta-aligned.apk
   "$BT/apksigner" verify --verbose libreta-release.apk
   ```

   `apksigner` reads `minSdkVersion` from the APK and signs with the v1 (JAR) scheme, which Android 4.4 requires.

The release APK is about 1 MB. The debug APK is about 3.5 MB.

## Prepare the tablet

1. On the tablet, open «Ajustes» (Settings), then «Acerca del dispositivo» (About device), and tap the build
   number seven times to enable developer options.
2. Open the developer options and turn on USB debugging.
3. Connect the tablet with a USB cable. When the tablet asks whether to trust the computer, accept.
4. Check that `adb` sees the tablet:

   ```bash
   adb devices
   ```

   The tablet appears with the state `device`.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The `-r` option keeps the app data when you reinstall. Then open Libreta on the tablet and
[connect it to Nextcloud](nextcloud.md).

## Use WSL

WSL 2 can't see USB devices. Build in WSL, but run the Windows `adb.exe` to talk to the tablet:

1. Install the Android Platform-Tools for Windows and note where `adb.exe` is.
2. Point `ADB` at it. From WSL, you reach the Windows drives through their mount points:

   ```bash
   export ADB=/path/to/platform-tools/adb.exe
   ```

3. Windows programs need Windows paths, so convert the APK path with `wslpath`:

   ```bash
   "$ADB" install -r "$(wslpath -w app/build/outputs/apk/debug/app-debug.apk)"
   ```

All the [scripts](../reference/scripts.md) read the `ADB` environment variable and convert paths for you.
Alternatively, forward the USB device into WSL with `usbipd`, and use a Linux `adb`.

## Next steps

- [Connect to Nextcloud](nextcloud.md)
- [Sign in with ChatGPT](chatgpt.md)
- [Deploy the dictation server](dictation-server.md)
- [Kiosk mode](kiosk-mode.md)
