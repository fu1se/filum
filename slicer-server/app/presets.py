"""Пресеты OrcaSlicer: системные профили + пользовательские из orca-sync.

CLI OrcaSlicer не разворачивает наследование у пресетов пластика (тип, плотность,
диапазоны температур остаются от умолчаний), поэтому мы сами склеиваем цепочку
inherits в один полный JSON и отдаём CLI уже его.
"""
import json
from pathlib import Path

KINDS = ("machine", "process", "filament")
# orca-sync хранит типы так же, как облако Orca
SYNC_KIND = {"printer": "machine", "print": "process", "filament": "filament",
             "machine": "machine", "process": "process"}


def parse_vector(raw: str) -> list[str]:
    """Значение-вектор из строки Orca: '"a b";"c"' или '245,245' или '0x0,350x0'."""
    if raw == "":
        return []
    if '"' not in raw and ";" not in raw:
        return raw.split(",")
    out, cur, quoted, i = [], [], False, 0
    while i < len(raw):
        ch = raw[i]
        if quoted:
            if ch == "\\" and i + 1 < len(raw):
                nxt = raw[i + 1]
                cur.append({"n": "\n", "r": "\r", "t": "\t"}.get(nxt, nxt))
                i += 2
                continue
            if ch == '"':
                quoted = False
            else:
                cur.append(ch)
        elif ch == '"':
            quoted = True
        elif ch == ";":
            out.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    out.append("".join(cur))
    return out


class PresetIndex:
    def __init__(self, system_dir: Path):
        self.system_dir = Path(system_dir)
        # name -> (kind, data, vendor, is_user)
        self.by_name: dict[str, tuple[str, dict, str, bool]] = {}
        self.list_keys: set[str] = set()
        for vendor_dir in sorted(p for p in self.system_dir.iterdir() if p.is_dir()):
            for kind in KINDS:
                d = vendor_dir / kind
                if not d.is_dir():
                    continue
                for f in d.rglob("*.json"):
                    try:
                        data = json.loads(f.read_text(encoding="utf-8"))
                    except Exception:
                        continue
                    name = data.get("name")
                    if not name or name in self.by_name:
                        continue
                    self.by_name[name] = (data.get("type") or kind, data, vendor_dir.name, False)
                    self.list_keys.update(k for k, v in data.items() if isinstance(v, list))
        self.system_names = set(self.by_name)

    # ---------- пользовательские пресеты ----------

    def set_user_presets(self, profiles: list[dict]):
        """profiles — содержимое orca-sync (content), значения там строками."""
        for name in [n for n, v in self.by_name.items() if v[3]]:
            del self.by_name[name]
        for content in profiles:
            kind = SYNC_KIND.get(str(content.get("type", "")))
            name = content.get("name")
            if not kind or not name:
                continue
            data = {}
            for k, v in content.items():
                if k in ("base_id", "updated_time", "user_id", "setting_id"):
                    continue
                if isinstance(v, str) and k in self.list_keys:
                    v = parse_vector(v)
                data[k] = v
            data["type"] = kind
            self.by_name[name] = (kind, data, "user", True)

    # ---------- наследование ----------

    def resolve(self, name: str, _seen=None) -> dict:
        """Полный пресет: родители сверху вниз, потомок перекрывает."""
        _seen = _seen or set()
        if name in _seen:
            raise ValueError(f"inheritance cycle: {name}")
        _seen.add(name)
        if name not in self.by_name:
            raise KeyError(f"preset not found: {name}")
        data = self.by_name[name][1]
        parent = data.get("inherits")
        merged = dict(self.resolve(parent, _seen)) if parent else {}
        merged.update(data)
        return merged

    def ancestors(self, name: str) -> list[str]:
        out = []
        while name and name in self.by_name and name not in out:
            out.append(name)
            name = self.by_name[name][1].get("inherits") or ""
        return out

    def kind(self, name: str) -> str:
        return self.by_name[name][0]

    def flattened(self, name: str, printer: str | None = None) -> dict:
        d = self.resolve(name)
        kind = self.kind(name)
        d.update(inherits="", type=kind, name=name)
        d.pop("instantiation", None)
        if kind == "machine":
            # CLI сверяет совместимость процесса с «системным» принтером
            d["from"] = "system"
        else:
            d["from"] = "User"
            # Совместимость мы уже проверили при выборе — привязываем явно к выбранному
            d["compatible_printers"] = [printer] if printer else []
            d["compatible_printers_condition"] = ""
            d["compatible_prints"] = []
            d["compatible_prints_condition"] = ""
        return d

    def write_flat(self, name: str, path: Path, printer: str | None = None) -> Path:
        path.write_text(json.dumps(self.flattened(name, printer), ensure_ascii=False), encoding="utf-8")
        return path

    # ---------- списки для интерфейса ----------

    def is_compatible(self, name: str, printer: str) -> bool:
        data = self.resolve(name)
        compat = data.get("compatible_printers") or []
        if isinstance(compat, str):
            compat = parse_vector(compat)
        if not compat:
            # Системные «для всех» (генерики) не показываем — их сотни; пользовательские — да
            return self.by_name[name][3]
        return any(p in compat for p in self.ancestors(printer))

    def list(self, kind: str, printer: str | None = None, name_filter: str = "") -> list[dict]:
        out = []
        for name, (k, data, vendor, is_user) in self.by_name.items():
            if k != kind:
                continue
            if not is_user and str(data.get("instantiation", "true")).lower() != "true":
                continue
            if kind == "machine":
                if not is_user and name_filter and name_filter.lower() not in name.lower():
                    continue
            elif printer and not self.is_compatible(name, printer):
                continue
            out.append({"name": name, "user": is_user})
        out.sort(key=lambda p: (not p["user"], p["name"].lower()))
        return out
