# Troubleshooting

## The app can't connect ("Not connected")

- **Same network.** The phone and the printer must be on the same network. Guest Wi-Fi and "client isolation"
  block this.
- **Moonraker.** Check it in a browser: `http://<printer-ip>:7125/printer/info` should return JSON.
  - K2-series printers have Moonraker out of the box.
  - On K1 and Ender-3 V3 printers you usually need root and Moonraker, for example via the
    [Creality Helper Script](https://github.com/Guilouz/Creality-Helper-Script).
- **Port.** If Moonraker runs on another port, enter the address as `ip:port`.

## The camera is black

- **Light.** The chamber may simply be dark: turn on the light in Control → Lighting.
- **Camera mode.** In ⚙ → Camera, try another mode:
  - *Auto* uses WebRTC (`:8000`) or MJPEG (`:8080`), depending on the printer;
  - if you run go2rtc, choose *go2rtc*.
- **Fullscreen.** Some printers can serve only one video stream at a time. Close Creality Print or other viewers,
  then tap ⟳ on the camera.

## The Slicer tab says "Slicer server is not configured" or "unavailable"

- **Server running.** Open `http://<server>:8090/health` in the phone's browser. It should show `{"status": "ok"}`.
- **URL in settings.** The URL in ⚙ must include the port, for example `http://192.168.1.10:8090`.
- **First start.** On the very first start the server image has to be built or downloaded; see
  [slicer-server.md](slicer-server.md).

## Slicing fails

The slice error and the OrcaSlicer log are shown in the job. Common causes:

- **"preset not found".** The printer profile no longer exists on the server, for example after orca-sync
  changes. Refresh the presets with ⟳.
- **OrcaSlicer crashes (exit code -11).** OrcaSlicer's command line crashes on some files. The server already
  works around 3MF files without project settings and 3MF files with inconsistent filament lists. If you hit
  another case, please open an issue and attach the model.

## Multicolor prints use the wrong spools

The server writes the spool mapping (`BOX_MODIFY_TN …`) at the start of the G-code. Check that:

- **Assignments.** Each color in the Slicer tab has a spool assigned.
- **File origin.** The file was sliced by Filum Slicer. Files from other slicers don't carry the mapping.
- **Spools loaded.** The CFS box reports the spools as loaded, not as empty slots.

## A CFS slot edit doesn't stick

- **Empty slot.** The printer may keep the data only once a spool is inserted.
- **Library.** Filaments that aren't in the printer's material library lose their id: the printer shows a generic
  material. Use [material-sync](../printer/material-sync/README.md) to add your own filaments to the library.

## No notifications

- **Permission.** Android 13+ needs notification permission: Settings → Apps → Filum → Notifications.
- **App setting.** "Notifications" must be on in ⚙.
- **Battery optimization.** Some phones kill background services aggressively. Set Filum's battery usage to
  "Unrestricted".

## Errors show only "Paused"

Filum reads the error from the Creality services on `:9999`. If that port isn't reachable (custom firmware without
the Creality services), only Klipper's own errors are available.
