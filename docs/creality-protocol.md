# How Creality OS works (for app developers)

These notes describe what Filum talks to on a Creality OS printer. They come from observing a K2 Plus (firmware
1.1.x, board code F008) and its stock software. Other models share most of this but may differ in details. None of
this is documented by Creality, so it may change with firmware updates.

A Creality OS printer runs two sets of software side by side:

1. **Klipper + Moonraker.** These are open source with Creality patches. Moonraker listens on `:7125` and Fluidd
   on `:4408` on the K2 series.
2. **Creality services.** These are closed source. The printer screen and Creality Print use them, and so does the
   Creality Cloud.
   - `web-server` provides a WebSocket on `:9999` and HTTP on `:80`.
   - `master-server` is the glue between the screen, CFS and Klipper.
   - `display-server` drives the screen.
   - `app-server` talks to the Creality Cloud.

Both sets run on the printer. Some data is available only on one side.

## Moonraker (`ws://printer:7125/websocket`)

This is the standard JSON-RPC used by Fluidd and Mainsail. Klipper objects that matter on Creality printers:

| Object | Notes |
|---|---|
| `print_stats`, `virtual_sdcard` | Standard. `virtual_sdcard` adds Creality fields `layer`, `layer_count`, `cur_print_data` |
| `heater_generic chamber_heater` | K2 series only. `M141 S<t>` is a Creality macro: up to 40° it keeps the temperature with the chamber fan, above 40° it uses the heater |
| `temperature_sensor chamber_temp` | Chamber temperature (K2, some K1 Max) |
| `output_pin fan0` / `fan1` / `fan2` | Part, side (AUX) and chamber fans. `M106 P<n> S<0-255>` / `M107 P<n>` are Creality macros. Printers without these have the standard `[fan]` |
| `output_pin LED` | Light: `SET_PIN PIN=LED VALUE=0/1` |
| `box` | CFS: `T1`…`T4` boxes with `color_value`, `material_type`, `remain_len`; plus `map` and `same_material` |
| `extruder.nozzle_diameter` | Set with `SET_NOZZLE_DIAMETER VALUE=0.6`, a Creality command. It is stored as `user_nozzle_diameter` in `printer.cfg` via `CXSAVE_CONFIG`, without a restart. The printer screen compares it with the nozzle in a G-code file |

Filum discovers features with `printer.objects.list` and `printer.gcode.help` and hides what's missing.

### Errors and "warnings"

Klipper on Creality OS has two kinds of errors:

- **Errors.** `!! {"code":"key839","msg":"…","values":[1,"B"]}` as a G-code response. Real shutdowns also set
  `print_stats.state = error`.
- **Warnings.** Creality modules call `gcode._respond_warning(...)`, which sends `## !{"code":"key358",…}`. The
  print just pauses: for Klipper this is a normal `paused` with an empty `message`. Filament runout
  (`filament_switch_sensor.py`) and many CFS problems work this way.

The code on the printer screen is **2000 + key** for Klipper and CFS errors (key839 → 2839), except filament runout
(key358 → 116). Codes below 2000 come from the Creality services themselves: AI camera (101 spaghetti, 104 foreign
object), power loss (115), CFS start (122) and others. Klipper never sees them. The current error is in the
`err` field on `:9999` (see below). The firmware keeps a history in
`/mnt/UDISK/creality/userdata/fault_code/fault_code_info.json`.

## Creality WebSocket (`ws://printer:9999`)

The printer pushes JSON status objects every second or so. They are partial: each message carries some fields.
Requests are `{"method":"get","params":{…}}` and `{"method":"set","params":{…}}`. Reply `ok` to `heart_beat`
messages.

Useful status fields:

| Field | Meaning |
|---|---|
| `model` | `F008` K2 Plus, `F012` K2 Pro, `F021` K2, `F001` Ender-3 V3, `F002` Ender-3 V3 Plus, `F005` Ender-3 V3 KE, `F018` Creality Hi; K1 series send names like `K1C`, `CR-K1 Max` |
| `webrtcSupport`, `video` | Camera: WebRTC on `:8000` (`/call/webrtc_local`) when `webrtcSupport` is 1, otherwise MJPEG on `:8080` (`/?action=stream`) |
| `cfsConnect` | A CFS is connected |
| `err` | `{"errcode": 116, "key": 1002, "value": "[ ]"}`: the error shown on the screen. 0 means none. Some firmware sends a bare number. The code can stay set after the problem is fixed, so treat it as stale while a print runs normally |
| `state`, `printProgress`, `printLeftTime`, `layer`, `TotalLayer` | Print status (Moonraker has the same) |

### Requests

| Request | Reply / effect |
|---|---|
| `get {"reqElapseVideoList":1}` | `elapseVideoList`: timelapse videos. Files are at `http://printer/downloads/video/<name>.mp4`. The server advertises `Accept-Ranges` but ignores Range, and `moov` is at the end, so download the whole file before playing it |
| `get {"boxsInfo":1}` | `boxsInfo.materialBoxs[]`: `type` 0 = CFS box (`id` 1…4, `temp`, `humidity`, `materials[]` with `id` 0…3 = slots A…D), `type` 1 = external spool holder (`id` 0, one material). Each material has `vendor`, `type`, `name`, `color` (`#0rrggbb`), `minTemp`, `maxTemp`, `pressure`, `percent`, `rfid`, `state` (0 = empty) |
| `get {"reqMaterials":1}` | `retMaterials[]`: the material library (~100 entries, about 460 KB). Each has `base` (`id`, `brand`, `name`, `meterialType` [sic], `minTemp`, `maxTemp`, `colors[]`) and `kvParam` (OrcaSlicer filament settings) |
| `set {"modifyMaterial":{…}}` | Edit a slot. See below |

### Editing a CFS slot

```json
{"method":"set","params":{"modifyMaterial":{
  "boxId":1, "id":0, "rfid":"00003", "type":"PETG", "vendor":"Generic", "name":"Generic PETG",
  "color":"#0ffa800", "minTemp":220.0, "maxTemp":270.0, "pressure":0.1,
  "selected":1, "percent":100, "editStatus":1, "state":1}}}
```

- `boxId` is the box (1…4) or 0 for the external holder. `id` is the slot (0…3 = A…D).
- **`minTemp` and `maxTemp` must be written with a decimal point** (`220.0`). An integer is stored as 0.
- `rfid` is the library id (`base.id`). If the id is not in the library, the firmware resets it to `0`. Klipper
  then reports `material_type: 0` with an empty type, and auto-refill can't match the material. To use your own
  materials, add them to the library first (see [material-sync](../printer/material-sync/README.md)).
- The change is applied asynchronously. Read `boxsInfo` again after about 1.5 s.

## CFS tool mapping

G-code tools T0…T15 map to slots through Klipper's `box.map` (T0 → `T1A`, T1 → `T1B`, …, T4 → `T2A`). The firmware
resets the table after every print. `BOX_MODIFY_TN T1A=T1C` sets an entry; Creality Print sends these before
`START_PRINT`. Filum Slicer writes them into the G-code.

## Material library files

These files are in `/mnt/UDISK/creality/userdata/box/`:

- `material_database.json`: the library returned by `reqMaterials`.
- `material_option.json`: vendor → type → names, for the picker on the printer screen.

The Creality services watch these files with inotify and reload them on change. Write them in place, keeping the
inode, rather than replacing them. `app-server` can also merge Creality Cloud user materials into the library.
