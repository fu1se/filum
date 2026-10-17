# Building and contributing

## Repository layout

| Path | Contents |
|---|---|
| `android/` | The app (Kotlin, Jetpack Compose). See [android/README.md](../android/README.md) for a code map |
| `slicer-server/` | Filum Slicer (Python standard library, OrcaSlicer CLI) |
| `printer/material-sync/` | Service that runs on the printer (Python 3.9, standard library) |
| `tools/` | i18n helpers used to move app strings into resources |
| `docs/` | Documentation and screenshots |

## Android app

You need JDK 17+ and the Android SDK (API 35). Android Studio works too.

```sh
cd android
echo "sdk.dir=$HOME/Android/Sdk" > local.properties   # or set ANDROID_HOME
./gradlew testDebugUnitTest      # unit tests
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Unit tests:**
- `FaultsTest` parses real firmware error lines.
- `StringsTest` checks that English and Russian strings have the same keys and placeholders.
- `PresetMatchTest` checks how printer profiles are matched to a model name.

### Testing without a printer

The app talks to two WebSockets on the printer: Moonraker (`:7125`) and the Creality services (`:9999`). For UI
work you can run a small fake. A Python script with the `websockets` package is enough:
- answer `printer.objects.list`, `printer.objects.subscribe` and `printer.info` on `:7125`;
- push `{"err": {"errcode": 116}, "model": "F008"}` on `:9999`.

In the Android emulator, add a printer with the address `10.0.2.2`, which is your computer.

### Release builds

`./gradlew assembleRelease` signs with the debug key so the APK installs directly. For a store release, configure
your own signing in `app/build.gradle.kts`.

## Slicer server

```sh
cd slicer-server
docker compose up -d --build
```

Without Docker, use any OrcaSlicer 2.4.x extracted from the AppImage:

```sh
ORCA_DIR=/path/to/squashfs-root SLICER_DATA=/tmp/filum-data SLICER_PORT=8090 python3 slicer-server/app/server.py
```

The server has no dependencies besides Python 3.10+ and the OrcaSlicer binary.

## CI

GitHub Actions in `.github/workflows/`:
- `android.yml` runs the unit tests and builds the APK on every push. A tag `v*` attaches the APK to a release.
- `slicer-image.yml` builds the Docker image and pushes it to `ghcr.io/<owner>/filum-slicer` on `main` and on tags.

## Contributing

- **Bugs and printer reports.** Please open an issue with the printer model, firmware version and what you
  expected. Support for printers other than the K2 Plus depends on such reports.
- **Pull requests.** Keep them focused. Run the unit tests before submitting.
- **New UI strings.** Add each one to both `res/values/strings.xml` and `res/values-ru/strings.xml`.
- **Code comments** are partly in Russian; new code can use English.
