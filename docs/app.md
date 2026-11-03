# Filum app guide

## First start

1. Install the APK: download it from Releases, or [build it](building.md).
2. Enter the printer's IP address. You'll find it on the printer screen under **Settings → Network**. A
   custom Moonraker port can be given as `ip:port`.
3. Optional fields:
   - **Name** — shown instead of the printer's hostname.
   - **Camera** — the type; *Auto* works for stock printers.
   - **Slicer server URL** — needed for the Slicer tab, see [slicer-server.md](slicer-server.md).
4. Android 13+ asks for permission to show notifications. Allow it if you want print notifications.

The app needs the printer and the phone to be in the same network. Nothing goes through the internet; the only
exception is MakerWorld, which the slicer server reaches.

## Several printers

Tap the printer name in the top bar to open the list of saved printers:

- **Switch** to another printer from the list.
- **Add printer…** — opens the same dialog as on first start.
- **Edit or delete** the active printer — use ⚙.

Notifications follow the active printer.

## Home

- **Error card.** Shown when the printer reports a problem. It explains what happened and what to do, and shows
  the code from the printer screen. This includes problems that only look like a pause in Klipper: filament
  runout, CFS errors, AI camera detections.
- **Camera.**
  - *Auto* uses WebRTC (port 8000) when the printer supports it (K2, Hi, newer K1C), otherwise MJPEG
    (port 8080).
  - *go2rtc* uses its first stream unless you put `?src=name` in the camera URL.
  - *Web page* opens any URL.
- **Print.** File, progress, layer, elapsed and remaining time; pause, resume and stop (with a confirmation).
- **Temperatures, speed and flow.** Speed is set with `M220`, flow with `M221`.

## Control

The app asks the printer which objects and G-code commands it has, and shows only the controls that exist.

| Section | What it does |
|---|---|
| Heating | Nozzle, bed and chamber with presets for PLA, PETG, ABS, ASA and TPU. The chamber heater appears only on printers that have one |
| Fans | Part cooling, side and chamber fans with Creality's `M106 P0–2`; the standard `[fan]` on other printers |
| Lighting | `SET_PIN PIN=LED` |
| Movement | Jog X/Y/Z, home, motors off. Blocked while printing |
| Nozzle | Sets the nozzle diameter on the printer (`SET_NOZZLE_DIAMETER`). The slicer then picks the matching printer preset |
| Spools | CFS boxes and the external spool holder. Tap a slot to choose the vendor, type and filament from the printer's library, plus the color, temperatures and pressure advance. **RFID tag** writes a spool tag the CFS reads by itself; see [rfid.md](rfid.md) |
| Extruder | Extrude or retract 5, 10 or 50 mm. The nozzle must be hot |

Filaments marked ★ in the slot editor come from your orca-sync presets, through
[material-sync](../printer/material-sync/README.md).

## Slicer

The Slicer tab needs [Filum Slicer](slicer-server.md).

1. **Model:**
   - a file from the phone (STL, 3MF, OBJ, STEP);
   - a model shared from another app with **Share → Slice in Filum**;
   - a MakerWorld model or link;
   - a recent model.
2. **Presets.** The printer preset is picked automatically from the printer model and nozzle diameter. ★ marks
   your own presets.
3. **Colors and CFS spools.** Each color of the model gets a spool, auto-matched by color and material; tap a row
   to change it. The filament preset follows the spool.
4. **Slice**, then **Preview**: a 3D view of the model and the toolpaths by layer, colored by line type or by
   spool color.
5. **Upload** or **Start print**. G-code goes from the server straight to the active printer.

## Files, timelapses, console

- **Files** — G-code on the printer, newest first. Tap a file to start it.
- **Timelapses** — the firmware's timelapse videos. They are cached on the phone before playback, because the
  printer's web server can't stream them. **Download** saves to Movies/Filum Timelapse.
- **Console** (top bar) — send G-code and see Klipper's replies.

## Notifications

A background service keeps a light connection to the active printer. It subscribes to print status only, without
temperatures.

| Channel | When |
|---|---|
| Print progress | Ongoing while printing: percent, layer, time left, Pause/Resume buttons |
| Print finished | Completed, cancelled, or paused without an error |
| Printer errors | Runout, CFS, AI camera, Klipper shutdown… with what to do |
| Monitoring | Android requires a notification for a background service even when idle. This one has the lowest importance and no status bar icon; you can turn the channel off in Android settings without losing the others |

Turn everything off with the **Notifications** switch in ⚙. The service starts with the app, after a reboot and
after an update.

## Updates

Filum checks GitHub for a new release when it starts (at most every 12 hours). You can also check by hand: ⚙ →
**Check for updates**. To update:
1. Tap **Update**: the APK is downloaded inside the app.
2. The first time, allow Filum to install apps. Android asks for this once.
3. The Android installer opens. Settings, printers and plugins are kept.

Releases from 0.2.2 on are signed with one permanent key. Builds before that were signed with a different key each,
so going from 0.2.1 or older to 0.2.2 needs one manual reinstall. The app detects this and says so instead of
failing.

## Plugins

The puzzle icon in the top bar opens the plugin manager:
- **Install.** From a file, from a URL, or from the bundled examples. Before installing, the manager shows the
  plugin's permissions.
- **Manage.** Turn plugins on and off, read their log, delete them.

The manager also shows **Verified plugins** from the catalog in this repository ([`plugins/catalog.json`](../plugins/catalog.json)):
- the catalog is re-read every time the manager opens;
- updates for installed plugins are offered automatically;
- each download is checked against the catalog's SHA-256.

Plugin controls appear on the Control tab. How to write plugins: [plugins.md](plugins.md).

## Language

The app follows the system language: English, or Russian on Russian systems. On Android 13+ you can choose the
language per app in Settings → Apps → Filum → Language.
