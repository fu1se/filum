# material-sync

A small service that runs **on the printer** (Creality K2 series). It adds your own filaments from **orca-sync** to
the firmware's material library. You can then assign, say, "NIT PETG" to a CFS slot or the spool holder from the
printer screen, from the Filum app or from Creality Print. The printer keeps a real material id for the slot, and
slicers find the matching preset by the vendor and name stored in the slot.

Without it, a filament that isn't in the library can still be written to a slot. But the firmware resets its id
to `0`, so Klipper reports `material_type: 0` with an empty type, and auto-refill can't match it.

## How it works

- **Where the library lives.** The firmware keeps its library in `/mnt/UDISK/creality/userdata/box`:
  - `material_database.json` holds the materials: `base` (id, vendor, name, type, temperatures) and `kvParam`
    (OrcaSlicer filament settings);
  - `material_option.json` maps vendor → type → names for the picker on the printer screen.
- **No restart needed.** The Creality services (`web-server`, `master-server`, `display-server`) watch these files
  with inotify and reload them themselves.
- **Which presets.** The service reads `/mnt/UDISK/orca-sync/data/db.json` and takes the filament presets made for
  this printer (`… @Creality K2 …`). Each name before ` @` becomes one material.
- **Nozzle.** Settings come from the preset for the nozzle set on the printer (`extruder.nozzle_diameter`, set
  with `SET_NOZZLE_DIAMETER`). If there's no such variant, the 0.4 preset is used. The library is rebuilt when
  the nozzle changes.
- **Inheritance.** `inherits` is resolved through your own presets. A system parent such as
  `CR-PETG @K2 Plus-all` or `Creality Generic PLA @K2-all` is taken from the printer's library as the `kvParam`
  template, and everything set in your preset is written over it.
- **Material id.** The id is `U` plus 4 hex digits of a hash of the name, so it stays stable between runs. It is 5
  characters long, like the firmware's own ids (`00003`, `E1001`); Klipper then sees the slot as
  `material_type: 0U1913`.
- **Duplicates.** Filaments that already exist in the library (copies of system presets: Hyper PETG, CR-TPU…) are
  skipped.
- **Own entries only.** The service marks its entries with `base.alias = "orca-sync"` and rebuilds them whenever
  orca-sync changes. If the firmware rewrites the library (a Creality Cloud update), they are added back. The rest
  of the library is left alone.
- **Backups.** Before the first write, `material_database.json.orig` and `material_option.json.orig` are saved.

## Install

```sh
scp material_sync.py root@PRINTER:/mnt/UDISK/material-sync/
scp material-sync.init root@PRINTER:/etc/init.d/material-sync
ssh root@PRINTER 'chmod +x /etc/init.d/material-sync && /etc/init.d/material-sync enable && /etc/init.d/material-sync start'
```

To see what would be added without writing anything:
`python3 /mnt/UDISK/material-sync/material_sync.py --once --dry-run`.
The log is in `logread | grep python3`.

**Uninstall:**
1. Run `/etc/init.d/material-sync disable && /etc/init.d/material-sync stop`.
2. Restore the `*.orig` files, unless `material_database.json` has been updated from the cloud since.

## CFS sync in OrcaSlicer

Since 2.4.x, OrcaSlicer matches a slot to a preset by the name and vendor stored in the slot. It then picks the
preset **without a parent** that has the same `filament_id` (`PresetBundle::sync_ams_list`). So:

- **Exact match.** Presets with their own `filament_id` and no `inherits` (made with "Create filament", or
  detached) are matched exactly.
- **Parent instead.** Presets that inherit from a system preset (NIT PETG ← CR-PETG) have the parent's
  `filament_id`, so Orca picks the parent. To get your preset, create it with "Create filament": Orca then gives
  it its own `filament_id`.

The Filum app and Filum Slicer match by name, so this works for every filament there.
