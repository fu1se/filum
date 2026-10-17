#!/usr/bin/env python3
"""Пластики из orca-sync → библиотека материалов принтера Creality K2.

Прошивка держит библиотеку в /mnt/UDISK/creality/userdata/box:
  material_database.json — пластики (base + kvParam с ключами OrcaSlicer),
  material_option.json   — производитель → тип → названия, для выбора на экране.
Демоны прошивки следят за этими файлами (inotify) и перечитывают их сами.

Скрипт добавляет туда пользовательские пластики из orca-sync: для каждого пресета
под K2 («… @Creality K2 …») — один материал с названием до « @», производителем,
типом, температурами и PA из пресета. Наследование (inherits) разворачивается по
другим пресетам пользователя, системный родитель берётся из библиотеки принтера.
После этого слот CFS можно назначить «НИТ PETG», а Orca/Creality Print при
синхронизации CFS находит пресет по производителю и названию.

Добавленные записи помечены base.alias = MARK и пересобираются при каждом запуске;
остальную библиотеку скрипт не меняет. Перед первой записью сохраняет копии *.orig.

  python3 material_sync.py --once --dry-run     показать, что будет добавлено
  python3 material_sync.py                      следить за orca-sync и синхронизировать
"""
import argparse
import copy
import hashlib
import json
import os
import re
import shutil
import sys
import time
import urllib.request

BOX_DIR = "/mnt/UDISK/creality/userdata/box"
ORCA_DB = "/mnt/UDISK/orca-sync/data/db.json"
MARK = "orca-sync"
PRINTER_TAG = "K2"   # пресеты для этого принтера: «… @Creality K2 Plus 0.4 nozzle»
MOONRAKER = "http://127.0.0.1:7125"


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def first(v):
    """Значение настройки Orca: строка, список строк или '"a";"b"'."""
    if isinstance(v, list):
        v = v[0] if v else None
    if isinstance(v, str):
        v = v.strip()
        if v.startswith('"') and '";"' in v:
            v = v.split('";"')[0]
        v = v.strip('"')
    return v


def num(v, cast=float):
    try:
        return cast(float(first(v)))
    except (TypeError, ValueError):
        return None


def load_profiles(path):
    """Пресеты пластиков всех пользователей orca-sync: имя → настройки."""
    with open(path, encoding="utf-8") as f:
        db = json.load(f)
    out = {}
    for user in (db.get("users") or {}).values():
        for p in (user.get("profiles") or {}).values():
            if p.get("deleted"):
                continue
            c = p.get("content")
            if isinstance(c, str):
                try:
                    c = json.loads(c)
                except ValueError:
                    continue
            if not isinstance(c, dict) or not c:
                continue
            if "filament_type" not in c and "filament_settings_id" not in c and "filament_vendor" not in c:
                continue  # принтер или процесс
            out[p.get("name") or c.get("name")] = c
    return out


def preset_nozzle(name):
    m = re.search(r"(\d+(?:\.\d+)?)\s*nozzle", name, re.I)
    return float(m.group(1)) if m else None


def printer_nozzle():
    """Диаметр сопла, заданный на принтере (SET_NOZZLE_DIAMETER); 0.4, если Klipper недоступен."""
    try:
        with urllib.request.urlopen(MOONRAKER + "/printer/objects/query?extruder=nozzle_diameter", timeout=5) as r:
            return float(json.loads(r.read())["result"]["status"]["extruder"]["nozzle_diameter"])
    except Exception:  # noqa: BLE001
        return None


def base_name(preset_name):
    return preset_name.split(" @")[0].strip()


def find_system(lib, parent, ftype):
    """Системный родитель пресета → запись библиотеки принтера."""
    by_name = {e["base"]["name"].lower(): e for e in lib}
    if parent:
        n = base_name(parent)
        for cand in (n, n.replace("Creality Generic ", "Generic "), n.replace("Creality ", "")):
            e = by_name.get(cand.lower())
            if e:
                return e
    if ftype:
        e = by_name.get(("Generic " + ftype).lower())
        if e:
            return e
        for e in lib:
            if e["base"].get("meterialType", "").upper() == ftype.upper() and e["base"].get("brand") == "Generic":
                return e
    return by_name.get("generic pla") or lib[0]


def resolve(profiles, name, lib, depth=0):
    """Настройки пресета с развёрнутым наследованием и системный шаблон из библиотеки."""
    c = profiles[name]
    parent = (first(c.get("inherits")) or "").strip()
    if parent and parent in profiles and depth < 8:
        merged, template = resolve(profiles, parent, lib, depth + 1)
        merged = dict(merged)
    else:
        merged, template = {}, None
    merged.update({k: v for k, v in c.items() if v not in (None, "", [])})
    if template is None:
        ftype = first(merged.get("filament_type"))
        fid = first(merged.get("filament_id"))
        template = next((e for e in lib if fid and e["base"]["id"] == str(fid)[-5:] and len(str(fid)) <= 6), None) \
            or find_system(lib, parent, ftype)
    return merged, template


def material_id(name, taken):
    """Свой 5-символьный id (как у прошивки: 00003, E1001): «U» + хэш названия."""
    h = hashlib.sha1(name.encode("utf-8")).hexdigest().upper()
    for i in range(0, len(h) - 4):
        mid = "U" + h[i:i + 4]
        if mid not in taken:
            return mid
    raise RuntimeError("нет свободного id")


def build(profiles, lib, nozzle=0.4):
    """Новые записи библиотеки по пресетам orca-sync."""
    system_names = {e["base"]["name"].lower() for e in lib if e["base"].get("alias") != MARK}
    taken = {e["base"]["id"] for e in lib if e["base"].get("alias") != MARK}
    groups = {}
    for name in profiles:
        if " @" not in name or PRINTER_TAG not in name.split(" @", 1)[1]:
            continue
        groups.setdefault(base_name(name), []).append(name)
    result = []
    for bname, names in sorted(groups.items()):
        if bname.lower() in system_names:
            continue  # такой пластик в библиотеке уже есть (копия системного пресета)
        # настройки пресета под сопло, которое стоит на принтере; нет такого — под 0.4
        def rank(n):
            d = preset_nozzle(n)
            return (d is None or abs(d - nozzle) > 0.01, d is None or abs(d - 0.4) > 0.01, n)
        names.sort(key=rank)
        conf, template = resolve(profiles, names[0], lib)
        e = copy.deepcopy(template)
        b, kv = e["base"], e["kvParam"]
        ftype = first(conf.get("filament_type")) or b.get("meterialType") or "PLA"
        vendor = first(conf.get("filament_vendor")) or "Generic"
        mid = material_id(bname, taken)
        taken.add(mid)
        lo = num(conf.get("nozzle_temperature_range_low"), int) or b.get("minTemp")
        hi = num(conf.get("nozzle_temperature_range_high"), int) or b.get("maxTemp")
        # kvParam — те же ключи, что у пресетов Orca: переносим всё, что задано в пресете
        for k in list(kv):
            if k in conf and k not in ("compatible_printers", "compatible_printers_condition", "inherits"):
                v = first(conf[k])
                if v is not None:
                    kv[k] = str(v)
        kv["filament_type"] = ftype
        kv["filament_vendor"] = vendor
        kv["nozzle_temperature_range_low"] = str(lo)
        kv["nozzle_temperature_range_high"] = str(hi)
        if "filament_id" in kv:
            kv["filament_id"] = mid
        colour = first(conf.get("default_filament_colour")) or first(conf.get("filament_colour"))
        b.update({
            "id": mid, "brand": vendor, "name": bname, "alias": MARK, "meterialType": ftype,
            "minTemp": lo, "maxTemp": hi, "rank": 100,
        })
        if colour and len(colour) >= 7:
            b["colors"] = [colour[:7].upper() + "FF"]
            b["mixColorInfoList"] = []
        d = num(conf.get("filament_density"))
        if d:
            b["density"] = d
        result.append(e)
    return result


def write_in_place(path, data):
    """Перезапись без замены inode: демоны следят за файлом через inotify."""
    text = json.dumps(data, ensure_ascii=False, indent=2)
    with open(path, "r+", encoding="utf-8") as f:
        f.seek(0)
        f.write(text)
        f.truncate()
        f.flush()
        os.fsync(f.fileno())


def backup(path):
    orig = path + ".orig"
    if not os.path.exists(orig):
        shutil.copy2(path, orig)
        log("копия:", orig)


def sync(orca_db, box_dir, dry_run=False, nozzle=None):
    db_path = os.path.join(box_dir, "material_database.json")
    opt_path = os.path.join(box_dir, "material_option.json")
    with open(db_path, encoding="utf-8") as f:
        db = json.load(f)
    with open(opt_path, encoding="utf-8") as f:
        opt = json.load(f)
    lib_all = db["result"]["list"]
    lib = [e for e in lib_all if e["base"].get("alias") != MARK]
    ours_before = [e for e in lib_all if e["base"].get("alias") == MARK]

    nozzle = nozzle or printer_nozzle() or 0.4
    new = build(load_profiles(orca_db), lib, nozzle)

    def key(es):
        return json.dumps(sorted((e["base"]["id"], e["base"]["name"], e["base"]["brand"], e["base"]["meterialType"],
                                  e["base"]["minTemp"], e["base"]["maxTemp"], e["kvParam"].get("pressure_advance"))
                                 for e in es), ensure_ascii=False)

    # material_option: убираем наши прошлые названия, добавляем текущие
    old_names = {(e["base"]["brand"], e["base"]["meterialType"], e["base"]["name"]) for e in ours_before}
    for brand, types in list(opt.items()):
        for t, names in list(types.items()):
            keep = [n for n in names.split("\n") if n and (brand, t, n) not in old_names]
            if keep:
                types[t] = "\n".join(keep)
            else:
                del types[t]
        if not types:
            del opt[brand]
    for e in new:
        b = e["base"]
        names = opt.setdefault(b["brand"], {}).get(b["meterialType"], "")
        lst = [n for n in names.split("\n") if n]
        if b["name"] not in lst:
            lst.append(b["name"])
        opt[b["brand"]][b["meterialType"]] = "\n".join(lst)

    for e in new:
        b = e["base"]
        log(("+ " if dry_run else "") + f'{b["id"]}  {b["brand"]} | {b["name"]} | {b["meterialType"]} '
            f'{b["minTemp"]}–{b["maxTemp"]}°  PA {e["kvParam"].get("pressure_advance")}')
    if key(new) == key(ours_before):
        return False
    if dry_run:
        log(f"dry-run: {len(new)} пластиков под сопло {nozzle}, файлы не изменены")
        return True

    backup(db_path)
    backup(opt_path)
    db["result"]["list"] = lib + new
    db["result"]["count"] = len(db["result"]["list"])
    write_in_place(db_path, db)
    write_in_place(opt_path, opt)
    log(f"библиотека обновлена: {len(new)} пластиков из orca-sync, сопло {nozzle}")
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--orca-db", default=ORCA_DB)
    ap.add_argument("--box-dir", default=BOX_DIR)
    ap.add_argument("--interval", type=int, default=30, help="как часто проверять изменения, с")
    ap.add_argument("--once", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--nozzle", type=float, help="диаметр сопла (по умолчанию — с принтера)")
    a = ap.parse_args()
    if a.once:
        sync(a.orca_db, a.box_dir, a.dry_run, a.nozzle)
        return
    stamp = None
    while True:
        try:
            files = (a.orca_db, os.path.join(a.box_dir, "material_database.json"))
            nozzle = printer_nozzle()
            # пересобираем, когда меняются пресеты, сопло или прошивка переписала библиотеку (обновление из облака)
            st = tuple(os.stat(p).st_mtime for p in files) + (nozzle,)
            if st != stamp:
                sync(a.orca_db, a.box_dir, nozzle=nozzle)
                stamp = tuple(os.stat(p).st_mtime for p in files) + (nozzle,)
        except Exception as e:  # noqa: BLE001 — сервис не должен падать
            log("ошибка:", e)
        time.sleep(a.interval)


if __name__ == "__main__":
    sys.exit(main())
