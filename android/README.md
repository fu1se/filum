# Filum — Android app

The Android app of [Filum](../README.md). It is written in Kotlin with Jetpack Compose and Material 3, and runs
on Android 8.0+ (API 26). Features and settings are described in the [app guide](../docs/app.md).

## Build

```sh
cd android
./gradlew testDebugUnitTest   # unit tests: fault parsing, string resources, preset matching
./gradlew assembleDebug       # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

You need JDK 17+ and the Android SDK. Point to the SDK with `sdk.dir=…` in `local.properties` (not committed) or
with `ANDROID_HOME`. The release build is signed with the debug key so that the APK can be installed directly;
set up your own signing before publishing to a store.

## Code map

```
app/src/main/java/app/filum/
├── FilumApp.kt              Application; tr() — string resources outside Compose
├── MainActivity.kt          "Share"/"Open with" for models; notification permission
├── PrinterViewModel.kt      saved printers, settings, timelapses
├── SlicerViewModel.kt       slicer state, CFS, presets, MakerWorld
├── data/
│   ├── PrinterClient.kt     Moonraker JSON-RPC (ws :7125), capability discovery
│   ├── PrinterCaps.kt       which controls a printer has (objects, G-code commands)
│   ├── PrinterState.kt      snapshot of Klipper objects
│   ├── CrealityStatus.kt    Creality services (ws :9999): current error, model, camera type
│   ├── Cfs.kt               CFS boxes, material library, slot editing (ws :9999)
│   ├── Faults.kt            printer error codes with explanations
│   ├── SlicerApi.kt         Filum Slicer REST client, preview binary formats
│   └── Timelapses.kt        timelapse list and cached playback
├── notify/                  PrintMonitorService (foreground service), BootReceiver
└── ui/                      Compose screens, Camera (WebView), Viewer3D (OpenGL ES 2.0)
```

Protocol details are in [docs/creality-protocol.md](../docs/creality-protocol.md).

## Translations

UI strings live in `res/values/strings.xml` (English, the default) and `res/values-ru/strings.xml`. `StringsTest`
checks that both files have the same keys and placeholders. To add a language, copy `values-ru` to
`values-<code>`, translate it, and add the locale to `res/xml/locales_config.xml`.

The original extraction was done with [`tools/i18n_extract.py`](../tools/i18n_extract.py) and
[`tools/i18n_apply.py`](../tools/i18n_apply.py). They move string literals into resources and turn Kotlin
templates into `%1$s` arguments.
