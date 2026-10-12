# Compatibility

Libreta targets an old, small tablet on purpose. This page lists the constraints that shape the code, the
toolchain and the checks that keep the app working on that device, and the limits on dependency versions. Read it
before you add a dependency or use a new platform API.

## Target device

The app is built for, and tested on, a Samsung Galaxy Tab 3 Lite (SM-T113). Treat the following as the device
class to support.

| Property | Value |
|---|---|
| Android | 4.4.4 (KitKat), API 19. The last security patch is from 2017. |
| Runtime | Dalvik. There is no ART. |
| CPU and ABI | Quad-core Cortex-A7 (Spreadtrum SC7730S), `armeabi-v7a`. |
| Memory | About 1 GB of RAM, with `ro.config.low_ram=true`. The heap limit per app is 64 MB (`largeHeap` would give 128 MB, and the app doesn't use it). |
| Screen | 7 inches, 600 x 1024 pixels at 160 dpi (mdpi). Capacitive, multi-touch, no active stylus. |
| Keys | A hardware menu key, so Android hides the overflow button. |
| Root | None. The build is a user build with release keys. |
| Network | Wi-Fi only. No mDNS, so `.local` names don't resolve. |

Consequences you can see in the code:

- **TLS.** The system TLS stack lacks AEAD suites, so the app bundles Conscrypt. The system trust store is from 2017.
  See [TLS](../design/tls.md).
- **No mDNS.** Use an IP address or a DNS name your router resolves. The optional certificate name exists for this.
- **The menu key.** The `AlwaysShowAction` lint check is disabled, because "if room" actions would disappear
  behind the hidden overflow button.
- **One ABI.** The APK includes only `armeabi-v7a` libraries (`abiFilters`), which saves several megabytes of
  Conscrypt's other native builds. It won't run on other ABIs.
- **Capacitive screen.** The stylus is a thin finger to the screen, and palm rejection uses the contact size. See
  [Ink design](../design/ink.md#two-fingers-and-the-stylus).

## Toolchain

| Piece | Version | Notes |
|---|---|---|
| Gradle | 9.8.0 | The wrapper verifies the distribution checksum. |
| Android Gradle Plugin | 9.4.1 | Sets no floor on `minSdk`. Verified by installing on the device. |
| Kotlin | 2.4.20 | The standard library runs on Dalvik and API 19. |
| JDK | 21, with bytecode 11 | AGP needs JDK 17 or later. D8 desugars the bytecode for API 19. |
| `compileSdk`, `targetSdk` | 36 | `minSdk` is 19. |
| Animal Sniffer | 2.0.1 | With the `gummy-bears-api-19` signature. See the next section. |

The app has no AndroidX, AppCompat or Compose. It uses `android.app.Activity` and the system Holo theme. The only
AndroidX artifact is `androidx.annotation`, which Markwon brings, and which contains only annotations.

Other consequences of API 19:

- There's no WorkManager (API 21), so sync uses an `IntentService`.
- There are no coroutines in the app. `BackgroundRunner` and plain executors do the work.
- `java.time` needs API 26, so dates use `SimpleDateFormat` (a new instance per call, because it isn't thread-safe).
- The app password can't be protected by the Android Keystore, which on API 19 has no symmetric keys. See
  [Architecture](../design/architecture.md#local-storage).

## API 19 guards

Two checks fail the build if code uses something that doesn't exist on the device.

**Android lint** runs `NewApi` on `:app` (`lintDebug`) and aborts on errors (`abortOnError = true`).

**Animal Sniffer** covers `:core`. Lint doesn't run `NewApi` on JVM modules, so it doesn't protect `:core`.
Animal Sniffer checks the compiled bytecode of `:core` against the API 19 signature. It catches:

- whole classes that API 19 lacks, such as `java.time`;
- JDK 8 methods that Kotlin exposes on its collections, such as `Map.getOrDefault`, which compiling against
  `android.jar` doesn't catch.

The signature is Gummy Bears (`gummy-bears-api-19`), not the plain `android-api-level-19` one. The plain signature
rejects `Integer.hashCode(int)`, which Kotlin uses in `data class`es and which D8 rewrites for API 19. Gummy Bears
lists the Java 8 methods that D8 backports, and still flags the things that fail on the device.

The `:core` `jar` task depends on the check, so every app build runs it. Tests run on the regular JDK.

## Dependency ceilings

Every dependency must work on API 19. Don't upgrade past these limits without verifying on the device.

| Dependency | Version | Why this version |
|---|---|---|
| OkHttp | 3.12.13 | The last branch that supports Android below 5. Version 4 and later need API 21. |
| Conscrypt (`conscrypt-android`) | 2.5.3 | Version 2.6 and later need `minSdk` 21. It brings TLS 1.2 with AES-GCM and ChaCha20-Poly1305. |
| Markwon | 4.6.2 | `minSdk` 16. Markdown becomes native spans, with no WebView. Its parser, commonmark-java 0.13.0, is Java 7 bytecode. |
| Markwon `ext-latex` (JLaTeXMath for Android 0.2.0) | 4.6.2 | Draws formulas as Android drawables, so it needs no WebView. Its fonts add about 550 KB to the APK. |
| `org.json` (tests only) | 20240303 | Android has its own `org.json`. In JVM tests, `android.jar` has only stubs of it. |

Notes:

- OkHttp 3.12 doesn't accept a certificate that has a common name but no subject alternative names. The certificate
  name setting works around it. See [TLS](../design/tls.md#the-certificate-name).
- `:core` has its own JSON codecs, because `org.json` isn't available to its tests, and a bundled copy would clash
  with Android's.
- `gradle/libs.versions.toml` holds every version, with the reason for each ceiling.

## Method count

A single `classes.dex` can hold 65,536 method references. A recent debug build, which isn't minified, has about
26,000 in one `classes.dex`, with OkHttp, Okio, Conscrypt, Markwon and JLaTeXMath. There's no need for multidex. R8 shrinks the
release build (`isMinifyEnabled` and `isShrinkResources`). `app/proguard-rules.pro` has only the rules the
dependencies don't ship: `-dontwarn` for Conscrypt classes that exist only on the device, and for Markwon's optional
image decoders.

If you add a large dependency, check the count before you merge.

## Memory

With a 64 MB heap shared by everything in the process, memory is the constraint that most often shapes a design.
Follow these rules:

- **Lists carry metadata only.** The note list reads titles and modification times (`NoteSummary`), not bodies.
- **Hash once.** Local file versions are cached by modification time and size, so a sync doesn't read every page
  image again.
- **Page bitmaps.** Page images are rendered in RGB 565, which halves the memory. They have no transparency. The view's
  cache is one view-sized bitmap.
- **Decode to size.** A page image is 1200 x 1702 pixels, which would take about 8 MB decoded. `Markdown.forNote` uses
  its own decoder that reduces each image to the screen width while decoding (`inSampleSize`).
- **Bound the working set.** The fill grid is capped at 1,500,000 cells (about 1.5 MB). Images sent for recognition
  have their longer side limited to 1600 pixels. The notebook index is in memory and fits hundreds of notes.
- **Threads cost stacks.** Executors end their thread after 30 seconds without work, and all HTTP clients share one
  dispatcher and connection pool.
- **Don't allocate while drawing.** `InkView` reuses one paint for all strokes in a redraw, builds paths once per
  drag, and the eraser's scan allocates nothing until a stroke changes.
- **Don't log content.** Log counts and exception types, never note names or text.

## Testing on the device class

The JVM tests don't prove that something runs on Dalvik. When you change anything in TLS, storage, drawing or the
dependencies, install a debug build on a real device (or an API 19 emulator image) and run the checklist in
[Testing](../testing.md). See [Build and install](../setup/build.md) for the commands.
