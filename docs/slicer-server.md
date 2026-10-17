# Filum Slicer

Filum Slicer is **OrcaSlicer (command line)** behind a small HTTP API. The Filum app uses it to slice models on a
machine in your network instead of on the phone. A minimal web UI at `http://<server>:8090` works from any
browser.

- **Models:** upload STL / 3MF / OBJ / STEP, or import from **MakerWorld** (search, link, choice of print
  profile).
- **Presets:** the OrcaSlicer system profiles (all Creality printers by default). Optionally, your own presets from
  an [orca-sync](#your-own-presets-orca-sync) server.
- **Multicolor 3MF:** each color is mapped to a CFS spool; [details](#multicolor-and-cfs).
- **Results:** time, weight and layers. Download the G-code, upload it to a printer or start the print.
  Thumbnails are rendered for the printer screen.
- **Previews:** the model mesh and the sliced toolpaths are served in compact binary formats for the app's 3D
  viewer.

> **Network only.** There is no authentication. Keep port 8090 inside your LAN and never forward it to the
> internet.

## Requirements

- An x86-64 Linux machine with Docker: a NAS, a home server or a PC. OrcaSlicer publishes Linux builds for
  x86-64 only, so a Raspberry Pi won't work.
- About 1.5 GB of disk for the image, plus space for models and G-code. The server keeps the last 60 of each.
- Slicing speed depends on the CPU. A typical model takes 10–60 s.

## Installation

### Docker Compose (any Linux)

```sh
git clone https://github.com/fu1se/filum.git
cd filum/slicer-server
docker compose up -d --build      # first build downloads OrcaSlicer, ~5 minutes
```

Open `http://<server-ip>:8090` in a browser to check it. Then enter the same address in the app: ⚙ → Slicer
server URL.

To use the prebuilt image instead of building it, delete the `build: .` line in `docker-compose.yml` and run
`docker compose up -d`. Images are published to `ghcr.io/fu1se/filum-slicer`.

### Plain `docker run`

```sh
docker run -d --name filum-slicer --restart unless-stopped \
  -p 8090:8090 -v filum-slicer-data:/data \
  ghcr.io/fu1se/filum-slicer:latest
```

### TrueNAS SCALE

**With the prebuilt image (recommended):**
1. Go to Apps → Discover Apps → ⋮ → **Install via YAML**.
2. Name the app `filum-slicer`.
3. Paste [`deploy/truenas.yaml`](../slicer-server/deploy/truenas.yaml).

**Without a registry** (the code runs from a dataset in a stock `ubuntu:24.04` container):
1. Copy `slicer-server/app` to a dataset, for example `/mnt/tank/apps/filum-slicer/app`.
2. Install via YAML with [`deploy/truenas-nobuild.yaml`](../slicer-server/deploy/truenas-nobuild.yaml). Fix the
   absolute host path in it first.
3. Wait for the first start: it installs packages and downloads OrcaSlicer (2–5 minutes, watch the app logs).
   Later restarts take seconds.
4. To update, replace the files in `app/` and restart the app.

### Unraid, Portainer, Synology and others

Use the image `ghcr.io/fu1se/filum-slicer:latest` with:
- port `8090`;
- a persistent volume mounted at `/data`;
- the environment variables below.

### Without Docker

Ubuntu 24.04 x86-64:

```sh
sudo bash slicer-server/app/install_orca.sh deps
sudo bash slicer-server/app/install_orca.sh orca /opt/orca 2.4.2
ORCA_DIR=/opt/orca SLICER_DATA=$HOME/filum-data python3 slicer-server/app/server.py
```

## Configuration

Everything is optional.

| Variable | Default | Meaning |
|---|---|---|
| `PRINTER_FILTER` | `Creality` | Which printer profiles to offer: a substring of the OrcaSlicer machine profile name. Your own presets are always shown |
| `ORCA_SYNC_URL` | — | orca-sync server, e.g. `http://192.168.1.50:8765` |
| `ORCA_SYNC_USER`, `ORCA_SYNC_PASSWORD` | — | orca-sync login. The password is used once; a refresh token is kept in `/data` |
| `ORCA_SYNC_REFRESH` | `600` | How often to re-read orca-sync, seconds |
| `MOONRAKER_URL` | — | Fallback printer for requests that don't name one. The app always passes its active printer |
| `DEFAULT_PRINTER`, `DEFAULT_PROCESS`, `DEFAULT_FILAMENT` | — | Preselected presets in the web UI |
| `ORCA_VERSION` | `2.4.2` | Build arg of the image / env of `start.sh` |
| `SLICER_PORT` | `8090` | HTTP port |
| `SLICER_DATA` | `/data` | Models, jobs, caches, tokens |

`/data` holds uploaded models (last 60), jobs and G-code (last 60), preview caches, and the orca-sync and Bambu
tokens.

## Your own presets (orca-sync)

orca-sync is a self-hosted server that implements the user-preset sync API of OrcaSlicer, so OrcaSlicer can sync
user presets with it instead of a cloud. It can be as small as a Python script on the printer itself. When it is configured, the server merges your presets with the
system ones; in the app they are marked with ★. The server resolves each preset's `inherits` chain itself, because
the OrcaSlicer CLI does not resolve it for filament presets (type, density and temperature ranges would otherwise
fall back to defaults).

To also see these filaments on the printer, when you pick a filament for a CFS slot, install
[material-sync](../printer/material-sync/README.md) on the printer.

## MakerWorld

Search and model pages work without logging in. Downloading a 3MF needs a Bambu account: in the MakerWorld tab,
enter your email and password, then the code from the email if Bambu asks for one.

If two-factor authentication is on, paste a token instead:
1. Log in at makerworld.com in a browser.
2. Open DevTools → Application → Cookies and copy the `token` value.
3. Paste it into "or paste a token".

A token lives about 3 months.

From a MakerWorld 3MF the server takes the objects, their arrangement and the number of colors. **The print
settings are yours** (printer, process, filaments), so profiles made for Bambu printers work too. Models are
re-arranged on your bed by default.

## Multicolor and CFS

G-code uses tools T0…T15. The printer firmware maps each tool to a physical slot through the table `box.map`
(T0 → key `T1A`, T1 → `T1B`, …, T4 → `T2A`), and it resets this table after every print. Creality Print sets the
table before a print with `BOX_MODIFY_TN T1A=T1C`. Filum Slicer writes the same commands at the start of the
G-code, before `START_PRINT`, so a file prints with the spools you chose no matter where you start it: from the
app, from Fluidd or from the printer screen.

Before slicing, a 3MF is normalized. The filament arrays in `project_settings.config` are resized to the number of
colors, because OrcaSlicer crashes on inconsistent ones. The colors are taken from the chosen spools.

## How it works

- **Presets** come from `resources/profiles` of OrcaSlicer, plus orca-sync (`/auth/v1/token` →
  `/api/v1/sync/pull`). Each preset is flattened into a full JSON before it is passed to the CLI.
- **Slicing:**
  `orca-slicer --slice N --arrange 1 --load-settings "machine;process" --load-filaments "f1;f2…"`. One job runs
  at a time; others wait in a queue.
- **Upload:** `POST http://<printer>:7125/server/files/upload`, with `print=true` to start.
- **Thumbnails:** the OrcaSlicer CLI can't render them without OpenGL, so the server renders the model itself
  (`thumbs.py`) and embeds 300×300 and 96×96 PNG blocks for the printer screen, Fluidd and the app.

## API

Every response is JSON unless noted. Errors look like `{"error": "…"}` with HTTP 4xx/5xx.

| Method | Path | Description |
|---|---|---|
| GET | `/health` | `ok` |
| GET | `/api/presets?printer=…` | printers, processes, filaments for that printer, defaults, orca-sync status, bed size |
| POST | `/api/presets/refresh` | re-read orca-sync |
| POST | `/api/models?name=file.stl` | body = the file; returns `id`, plates, filament slots |
| GET | `/api/models` | recent models |
| GET | `/api/models/{id}/thumb` | PNG |
| GET | `/api/models/{id}/mesh` | mesh for the 3D viewer (gzip, format `K2M1`, see `preview.py`) |
| GET | `/api/cfs?host=PRINTER[&printer=PRESET]` | CFS spools of that printer and a suggested filament preset for each |
| POST | `/api/slice` | `{"model_id", "printer", "process", "filaments": […], "cfs": ["T1C","T1A"], "plate": 0, "arrange": true, "printer_host": "192.168.1.50", "upload": false, "start": false}` → job |
| GET | `/api/jobs` / `/api/jobs/{id}` | jobs; `state`: `queued` / `slicing` / `done` / `error`, `results` per plate |
| GET | `/api/jobs/{id}/gcode/{plate}` | G-code download |
| GET | `/api/jobs/{id}/preview/{plate}` | toolpaths by layer (gzip, format `K2G1`) |
| GET | `/api/jobs/{id}/thumb/{plate}` | thumbnail from the G-code (PNG) |
| POST | `/api/jobs/{id}/upload` | `{"plate": 1, "start": true, "printer_host": "192.168.1.50"}` |
| GET | `/api/makerworld/status` | login state |
| POST | `/api/makerworld/login` | `{"account","password"}` → maybe `need_code`; `{"account","code"}`; or `{"token"}` |
| POST | `/api/makerworld/logout` | |
| GET | `/api/makerworld/search?q=…&offset=0` | search |
| GET | `/api/makerworld/resolve?url=…` | link → design id and profile id |
| GET | `/api/makerworld/design/{id or link}` | model and its print profiles |
| POST | `/api/makerworld/import` | `{"design_id", "instance_id"}` → model |

`printer_host` and `host` accept `host` or `host:port` only (Moonraker, port 7125 by default). Nothing else is
accepted, so the server can't be used to reach arbitrary URLs.
