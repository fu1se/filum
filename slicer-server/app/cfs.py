"""CFS (Creality Filament System): катушки в боксах и привязка цветов модели к ним.

Состояние — объект Klipper `box` через Moonraker. G-code использует T0…T15, а прошивка
переводит инструмент в физический слот по таблице `box.map` (T0 = ключ T1A, T1 = T1B, …).
Creality Print перед печатью задаёт её командой `BOX_MODIFY_TN T1A=T1C`; таблица
сбрасывается после печати. Мы вставляем эти команды в начало G-code — файл печатает
выбранными катушками, откуда бы его ни запустили.
"""
import json
import re
import urllib.request
from pathlib import Path

LETTERS = "ABCD"
MATERIALS: dict[str, dict] = json.loads((Path(__file__).parent / "cfs_materials.json").read_text(encoding="utf-8"))


def tool_key(tool: int) -> str:
    """Номер инструмента G-code → ключ таблицы прошивки: 0 → T1A, 5 → T2B."""
    return f"T{tool // 4 + 1}{LETTERS[tool % 4]}"


def slot_index(slot_id: str) -> int:
    m = re.fullmatch(r"T([1-4])([A-D])", slot_id or "")
    if not m:
        raise ValueError(f"invalid CFS slot: {slot_id}")
    return (int(m.group(1)) - 1) * 4 + LETTERS.index(m.group(2))


def _color(v) -> str | None:
    v = str(v or "")
    if len(v) == 7 and all(c in "0123456789abcdefABCDEF" for c in v):  # '0RRGGBB'
        return "#" + v[1:].upper()
    if len(v) == 6:
        return "#" + v.upper()
    return None


def state(moonraker: str) -> dict:
    """Катушки всех подключённых боксов."""
    with urllib.request.urlopen(moonraker.rstrip("/") + "/printer/objects/query?box", timeout=8) as r:
        box = json.loads(r.read())["result"]["status"].get("box") or {}
    types_by_slot = {}
    for entry in box.get("same_material") or []:
        if isinstance(entry, list) and len(entry) >= 4:
            for s in entry[2] or []:
                types_by_slot[s] = entry[3]
    slots = []
    for n in range(1, 5):
        b = box.get(f"T{n}") or {}
        if b.get("state") != "connect":
            continue
        colors = b.get("color_value") or []
        codes = b.get("material_type") or []
        remain = b.get("remain_len") or []
        for i, letter in enumerate(LETTERS):
            sid = f"T{n}{letter}"
            code = str(codes[i]) if i < len(codes) else "-1"
            color = _color(colors[i] if i < len(colors) else None)
            empty = code in ("-1", "None", "") or color is None
            mat = MATERIALS.get(code[1:]) if len(code) == 6 else None
            try:
                rem = int(remain[i])
            except (ValueError, TypeError, IndexError):
                rem = None
            slots.append({
                "id": sid, "box": n, "letter": letter, "tool_index": (n - 1) * 4 + i,
                "empty": empty, "color": color,
                "type": (mat or {}).get("type") or types_by_slot.get(sid),
                "brand": (mat or {}).get("brand"), "name": (mat or {}).get("name"),
                "remain": None if empty else rem,
            })
    return {"connected": bool(slots), "slots": slots, "auto_refill": bool(box.get("auto_refill"))}


def _norm(s: str) -> str:
    return re.sub(r"[^a-z0-9а-я]+", "", (s or "").lower())


def suggest_preset(idx, printer: str, slot: dict) -> str | None:
    """Пресет пластика под катушку: сначала по названию (★ ваши впереди), затем по типу."""
    if slot.get("empty"):
        return None
    candidates = idx.list("filament", printer)
    if not candidates:
        return None
    name = _norm(slot.get("name"))
    brand = _norm(slot.get("brand"))
    want_type = (slot.get("type") or "").upper()

    def ftype(n):
        t = idx.resolve(n).get("filament_type")
        return (t[0] if isinstance(t, list) and t else t or "").upper()

    best, score_best = None, -1
    for c in candidates:
        n = _norm(c["name"].split("@")[0])
        score = 0
        if name and name == n:  # «НИТ PETG» → «НИТ PETG @…», а не «3D Hero … НИТ PETG»
            score += 10
        if name and name in n:
            score += 6
            if brand and brand != "generic" and brand in n:
                score += 2
        t = ftype(c["name"])
        if want_type and (t == want_type or (want_type == "PETG" and t in ("PET", "PETG"))):
            score += 3
        if "generic" in n and not name:
            score += 1
        if c["user"]:
            score += 1
        if score > score_best:
            best, score_best = c["name"], score
    return best if score_best >= 3 else None


def mapping_gcode(slot_ids: list[str]) -> str:
    """Команды прошивки: инструмент i печатает из выбранного слота."""
    lines = [f"BOX_MODIFY_TN {tool_key(i)}={sid}" for i, sid in enumerate(slot_ids) if sid]
    return ("; Filum Slicer: model colors -> CFS spools\n" + "\n".join(lines) + "\n") if lines else ""
