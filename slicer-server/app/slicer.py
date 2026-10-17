"""Модели, очередь нарезки через OrcaSlicer CLI и отправка G-code в Moonraker."""
import http.client
import json
import os
import queue
import re
import shutil
import subprocess
import threading
import time
import urllib.parse
import uuid
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

import cfs
import preview
import thumbs

MODEL_EXT = {".stl", ".3mf", ".obj", ".step", ".stp"}
KEEP_MODELS = 60
KEEP_JOBS = 60
SLICE_TIMEOUT = 30 * 60


def safe_name(name: str) -> str:
    name = os.path.basename(name or "model.stl")
    name = re.sub(r"[^\w.\- ()\[\]]+", "_", name).strip(" .") or "model.stl"
    return name[:120]


def model_info(path: Path) -> dict:
    """Для 3MF: число столов и пластиков (цвет/тип по слотам)."""
    info = {"plates": [1], "filaments": [{"slot": 1, "type": None, "color": None}]}
    if path.suffix.lower() != ".3mf":
        return info
    try:
        with zipfile.ZipFile(path) as z:
            names = set(z.namelist())
            if "Metadata/project_settings.config" in names:
                ps = json.loads(z.read("Metadata/project_settings.config"))
                colors = ps.get("filament_colour") or []
                types = ps.get("filament_type") or []
                n = max(len(colors), len(types), 1)
                info["filaments"] = [{
                    "slot": i + 1,
                    "type": types[i] if i < len(types) else None,
                    "color": colors[i] if i < len(colors) else None,
                } for i in range(n)]
            if "Metadata/model_settings.config" in names:
                root = ET.fromstring(z.read("Metadata/model_settings.config"))
                plates = []
                for p in root.iter("plate"):
                    for m in p.iter("metadata"):
                        if m.get("key") == "plater_id":
                            plates.append(int(m.get("value")))
                if plates:
                    info["plates"] = sorted(set(plates))
            info["has_thumb"] = "Metadata/plate_1.png" in names
    except Exception as e:  # битый/необычный 3MF — режем как есть
        info["warning"] = f"could not parse 3MF: {e}"
    return info


def gcode_summary(path: Path) -> dict:
    """Время, вес и тип пластика из комментариев G-code."""
    size = path.stat().st_size
    with open(path, "rb") as f:
        head = f.read(64 * 1024).decode(errors="replace")
        f.seek(max(0, size - 400 * 1024))
        tail = f.read().decode(errors="replace")
    text = head + "\n" + tail

    def find(pattern):
        m = re.search(pattern, text, re.M)
        return m.group(1).strip() if m else None

    return {
        "size": size,
        "time": find(r"^; estimated printing time \(normal mode\) = (.+)$")
        or find(r"^; model printing time: ([^;]+)"),
        "grams": find(r"^; total filament used \[g\] = ([\d.]+)"),
        "meters": find(r"^; total filament length \[mm\] = ([\d.]+)"),
        "filament_type": find(r"^; filament_type = (.+)$"),
        "layers": find(r"^; total layer number: (\d+)"),
    }


def _normalize_3mf(src: Path, dst: Path, n: int, colors: list):
    """Копия 3MF с согласованными массивами пластиков (на рассогласованных Orca падает
    с segfault) и цветами выбранных катушек CFS."""
    with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            data = zin.read(item.filename)
            if item.filename == "Metadata/project_settings.config":
                try:
                    ps = json.loads(data)
                    data = json.dumps(_fix_project(ps, n, colors), ensure_ascii=False, indent=1).encode()
                except Exception:
                    pass
            zout.writestr(item, data)


def _fix_project(ps: dict, n: int, colors: list) -> dict:
    def fit(v, size):
        if not isinstance(v, list) or not v:
            return v
        return (v + [v[-1]] * size)[:size]

    for k, v in list(ps.items()):
        if k.startswith("filament_") and isinstance(v, list) and len(v) != n and k != "filament_map_mode":
            ps[k] = fit(v, n)
    cols = ps.get("filament_colour")
    if isinstance(cols, list):
        ps["filament_colour"] = [colors[i] if i < len(colors) and colors[i] else c for i, c in enumerate(fit(cols, n))]
    if isinstance(ps.get("flush_volumes_vector"), list) and len(ps["flush_volumes_vector"]) != 2 * n:
        ps["flush_volumes_vector"] = fit(ps["flush_volumes_vector"], 2 * n)
    m = ps.get("flush_volumes_matrix")
    if isinstance(m, list) and len(m) != n * n:
        try:
            other = max(float(x) for x in m) if m else 280.0
        except ValueError:
            other = 280.0
        ps["flush_volumes_matrix"] = ["0" if i // n == i % n else str(int(other) or 280) for i in range(n * n)]
    if "filament_map" in ps:
        ps["filament_map"] = ["1"] * n
    return ps


def _insert_after_marker(path: Path, marker: str, text: str):
    """Вставляет строки после маркера в начале G-code (до стартового кода принтера)."""
    if not text:
        return
    tmp = path.with_suffix(".tmp")
    done = False
    with open(path, "r", errors="surrogateescape") as src, open(tmp, "w", errors="surrogateescape") as dst:
        for i, line in enumerate(src):
            dst.write(line)
            if not done and line.startswith(marker):
                dst.write(text)
                done = True
                break
        if not done:
            # маркера нет — ставим в самое начало
            src.seek(0)
            dst.seek(0)
            dst.truncate()
            dst.write(text)
        while chunk := src.read(1 << 20):
            dst.write(chunk)
    tmp.replace(path)


def compact_time(t: str | None) -> str:
    if not t:
        return ""
    parts = re.findall(r"(\d+)([dhms])", t)
    keep = [f"{n}{u}" for n, u in parts if u != "s"] or [f"{n}{u}" for n, u in parts]
    return "".join(keep)


_HOST_RE = re.compile(r"^[A-Za-z0-9.\-]+(:\d{1,5})?$")


def moonraker_url(host: str | None, default: str = "") -> str:
    """Адрес Moonraker: из запроса (хост или хост:порт, порт по умолчанию 7125) или из MOONRAKER_URL.
    Принимаем только имя/IP и порт — без схемы и пути, чтобы сервер не ходил куда попало."""
    host = (host or "").strip()
    if not host:
        return default.rstrip("/")
    if not _HOST_RE.match(host):
        raise ValueError(f"invalid printer address: {host}")
    return f"http://{host if ':' in host else host + ':7125'}"


class Slicer:
    def __init__(self, data_dir: Path, orca_bin: Path, presets_provider, moonraker_url: str):
        self.data = data_dir
        self.models_dir = data_dir / "models"
        self.jobs_dir = data_dir / "jobs"
        self.work_home = data_dir / "home"
        for d in (self.models_dir, self.jobs_dir, self.work_home):
            d.mkdir(parents=True, exist_ok=True)
        self.orca = orca_bin
        self.presets = presets_provider  # () -> PresetIndex
        self.moonraker = moonraker_url.rstrip("/")
        self.jobs: dict[str, dict] = {}
        self.lock = threading.Lock()
        self.q: queue.Queue[str] = queue.Queue()
        self._load_jobs()
        threading.Thread(target=self._worker, daemon=True).start()

    # ---------- модели ----------

    def add_model(self, filename: str, stream, length: int, source: str = "upload", meta: dict | None = None) -> dict:
        name = safe_name(filename)
        if Path(name).suffix.lower() not in MODEL_EXT:
            raise ValueError("Supported formats: STL, 3MF, OBJ, STEP")
        mid = uuid.uuid4().hex[:12]
        d = self.models_dir / mid
        d.mkdir()
        path = d / name
        with open(path, "wb") as f:
            left = length
            while left > 0:
                chunk = stream.read(min(1 << 16, left))
                if not chunk:
                    break
                f.write(chunk)
                left -= len(chunk)
        return self._register_model(mid, path, source, meta)

    def register_file(self, path: Path, source: str, meta: dict | None = None) -> dict:
        mid = uuid.uuid4().hex[:12]
        d = self.models_dir / mid
        d.mkdir()
        dest = d / safe_name(path.name)
        shutil.move(str(path), dest)
        return self._register_model(mid, dest, source, meta)

    def _register_model(self, mid: str, path: Path, source: str, meta: dict | None) -> dict:
        m = {"id": mid, "name": path.name, "source": source, "created": time.time(),
             "size": path.stat().st_size, "info": model_info(path), **(meta or {})}
        (path.parent / "model.json").write_text(json.dumps(m, ensure_ascii=False))
        self._trim(self.models_dir, KEEP_MODELS)
        return m

    def model(self, mid: str) -> tuple[dict, Path]:
        if not re.fullmatch(r"[0-9a-f]{12}", mid or ""):
            raise KeyError("model not found")
        d = self.models_dir / mid
        m = json.loads((d / "model.json").read_text())
        return m, d / m["name"]

    def models(self) -> list[dict]:
        out = []
        for d in self.models_dir.iterdir():
            try:
                out.append(json.loads((d / "model.json").read_text()))
            except Exception:
                pass
        return sorted(out, key=lambda m: -m["created"])

    def model_thumb(self, mid: str, plate: int = 1) -> bytes | None:
        _, path = self.model(mid)
        if path.suffix.lower() != ".3mf":
            return None
        with zipfile.ZipFile(path) as z:
            for n in (f"Metadata/plate_{plate}.png", "Metadata/plate_1.png", "Metadata/thumbnail.png"):
                if n in z.namelist():
                    return z.read(n)
        return None

    @staticmethod
    def _trim(root: Path, keep: int):
        dirs = sorted((d for d in root.iterdir() if d.is_dir()), key=lambda d: d.stat().st_mtime, reverse=True)
        for d in dirs[keep:]:
            shutil.rmtree(d, ignore_errors=True)

    # ---------- задания ----------

    def _load_jobs(self):
        for d in self.jobs_dir.iterdir():
            try:
                j = json.loads((d / "job.json").read_text())
            except Exception:
                continue
            if j["state"] in ("queued", "slicing"):
                j["state"], j["error"] = "error", "the server restarted while slicing"
            self.jobs[j["id"]] = j

    def _save_job(self, j: dict):
        (self.jobs_dir / j["id"] / "job.json").write_text(json.dumps(j, ensure_ascii=False))

    def submit(self, req: dict) -> dict:
        m, _ = self.model(req["model_id"])
        idx = self.presets()
        printer = req.get("printer")
        process = req.get("process")
        filaments = [f for f in (req.get("filaments") or []) if f]
        for name in [printer, process, *filaments]:
            if not name or name not in idx.by_name:
                raise ValueError(f"preset not found: {name}")
        if not filaments:
            raise ValueError("no filament selected")
        # Катушки CFS по слотам модели: ["T1C", "T1A", …] (None — не назначать)
        cfs_slots = [s or None for s in (req.get("cfs") or [])]
        for s in cfs_slots:
            if s:
                cfs.slot_index(s)
        cfs_colors = [None] * len(cfs_slots)
        moonraker = moonraker_url(req.get("printer_host"), self.moonraker)
        if any(cfs_slots) and moonraker:
            try:
                by_id = {s["id"]: s for s in cfs.state(moonraker)["slots"]}
                cfs_colors = [by_id.get(s, {}).get("color") if s else None for s in cfs_slots]
            except Exception:
                pass  # принтер недоступен — цвета останутся из модели
        jid = uuid.uuid4().hex[:12]
        (self.jobs_dir / jid).mkdir()
        j = {
            "id": jid, "model_id": m["id"], "model_name": m["name"], "created": time.time(),
            "printer": printer, "process": process, "filaments": filaments,
            "cfs": cfs_slots, "cfs_colors": cfs_colors, "moonraker": moonraker,
            "plate": int(req.get("plate") or 0), "arrange": bool(req.get("arrange", True)),
            "upload": bool(req.get("upload", False)), "start": bool(req.get("start", False)),
            "state": "queued", "error": None, "log": "", "results": [],
        }
        with self.lock:
            self.jobs[jid] = j
            self._save_job(j)
        self.q.put(jid)
        self._trim(self.jobs_dir, KEEP_JOBS)
        return j

    def job(self, jid: str) -> dict:
        with self.lock:
            if jid not in self.jobs:
                raise KeyError("job not found")
            j = dict(self.jobs[jid])
        j["queue_position"] = list(self.q.queue).index(jid) + 1 if j["state"] == "queued" and jid in self.q.queue else 0
        return j

    def list_jobs(self) -> list[dict]:
        with self.lock:
            return sorted((dict(j, log="") for j in self.jobs.values()), key=lambda j: -j["created"])[:30]

    def gcode_path(self, jid: str, plate: int) -> Path:
        j = self.job(jid)
        for r in j["results"]:
            if r["plate"] == plate:
                return self.jobs_dir / jid / "out" / r["file"]
        raise KeyError("no such plate")

    def _update(self, j: dict, **kw):
        with self.lock:
            j.update(kw)
            self._save_job(j)

    def _worker(self):
        while True:
            jid = self.q.get()
            j = self.jobs.get(jid)
            if not j:
                continue
            try:
                self._run(j)
            except Exception as e:
                self._update(j, state="error", error=str(e))

    def _run(self, j: dict):
        self._update(j, state="slicing", started=time.time())
        jd = self.jobs_dir / j["id"]
        out = jd / "out"
        out.mkdir(exist_ok=True)
        m, model_path = self.model(j["model_id"])
        idx = self.presets()
        printer = j["printer"]

        # Слотов пластика столько, сколько в 3MF; недостающие — последним выбранным
        slots = max(len(m["info"].get("filaments") or [1]), 1)
        fils = (j["filaments"] + [j["filaments"][-1]] * slots)[:max(slots, len(j["filaments"]))]
        idx.write_flat(printer, jd / "machine.json")
        idx.write_flat(j["process"], jd / "process.json", printer)
        fil_files = []
        for i, name in enumerate(fils):
            fil_files.append(str(idx.write_flat(name, jd / f"filament_{i + 1}.json", printer)))

        # Orca запускается с LC_ALL=C и не открывает файлы с не-ASCII именами —
        # даём ему копию модели с латинским именем
        src = jd / ("model" + model_path.suffix.lower())
        if not src.exists():
            if model_path.suffix.lower() == ".3mf":
                _normalize_3mf(model_path, src, len(fils), j.get("cfs_colors") or [])
            else:
                try:
                    os.link(model_path, src)
                except OSError:
                    shutil.copyfile(model_path, src)

        cmd = [str(self.orca), "--slice", str(j["plate"]),
               "--arrange", "1" if j["arrange"] else "0",
               "--allow-newer-file",
               "--outputdir", str(out),
               "--load-settings", f"{jd / 'machine.json'};{jd / 'process.json'}",
               "--load-filaments", ";".join(fil_files),
               str(src)]
        env = dict(os.environ, HOME=str(self.work_home))
        t0 = time.time()
        p = subprocess.run(cmd, cwd=jd, env=env, capture_output=True, text=True, timeout=SLICE_TIMEOUT)
        log = (p.stdout + p.stderr)[-20000:]
        result = {}
        try:
            result = json.loads((out / "result.json").read_text())
        except Exception:
            pass
        gcodes = sorted(out.glob("plate_*.gcode"), key=lambda f: int(re.findall(r"\d+", f.stem)[0]))
        if p.returncode != 0 or not gcodes:
            msg = result.get("error_string") or f"OrcaSlicer exited with code {p.returncode}"
            self._update(j, state="error", error=msg, log=log)
            return

        base = Path(m["name"]).stem
        results = []
        for g in gcodes:
            plate = int(re.findall(r"\d+", g.stem)[0])
            s = gcode_summary(g)
            ftype = (s.get("filament_type") or "").split(";")[0].strip()
            parts = [base, ftype, compact_time(s.get("time"))]
            if len(gcodes) > 1:
                parts.insert(1, f"plate{plate}")
            nice = safe_name("_".join(x for x in parts if x) + ".gcode")
            g.rename(out / nice)
            if any(j.get("cfs") or []):
                _insert_after_marker(out / nice, "; EXECUTABLE_BLOCK_START", cfs.mapping_gcode(j["cfs"]))
            self._add_thumbnails(out / nice, model_path, m, idx, fils[0],
                                 next((c for c in (j.get("cfs_colors") or []) if c), None))
            results.append({"plate": plate, "file": nice, **s, "uploaded": False, "printing": False})
        self._update(j, results=results, log=log, slice_seconds=round(time.time() - t0, 1))

        if j["upload"] and (j.get("moonraker") or self.moonraker):
            for i, r in enumerate(results):
                try:
                    # Сразу печатаем, только если стол один — иначе пусть выбирают
                    self._upload(j, r, start=j["start"] and len(results) == 1)
                except Exception as e:
                    with self.lock:
                        r["upload_error"] = str(e)
                        self._save_job(j)
        self._update(j, state="done", finished=time.time())

    def _add_thumbnails(self, gcode: Path, model_path: Path, m: dict, idx, filament: str, color: str | None = None):
        """Orca CLI не рисует миниатюры (нужен OpenGL) — рендерим сами: их показывают
        экран принтера, Fluidd и приложение."""
        try:
            color = color or (m["info"].get("filaments") or [{}])[0].get("color")
            if not color:
                fc = idx.resolve(filament).get("filament_colour")
                color = fc[0] if isinstance(fc, list) and fc else fc
            thumbs.inject(gcode, preview.mesh_tris(model_path, self.orca), color)
        except Exception as e:  # без миниатюры G-code всё равно годен
            print(f"thumbnail not created: {e}", flush=True)

    # ---------- Moonraker ----------

    def upload(self, jid: str, plate: int, start: bool, host: str | None = None) -> dict:
        if jid not in self.jobs:
            raise KeyError("job not found")
        j = self.jobs[jid]
        r = next((r for r in j["results"] if r["plate"] == plate), None)
        if not r:
            raise KeyError("no such plate")
        self._upload(j, r, start, moonraker_url(host, j.get("moonraker") or self.moonraker))
        return self.job(jid)

    def _upload(self, j: dict, r: dict, start: bool, moonraker: str | None = None):
        moonraker = moonraker or j.get("moonraker") or self.moonraker
        if not moonraker:
            raise ValueError("printer address is unknown: pass printer_host or set MOONRAKER_URL")
        path = self.jobs_dir / j["id"] / "out" / r["file"]
        u = urllib.parse.urlsplit(moonraker)
        boundary = "----filum" + uuid.uuid4().hex
        fields = [("root", "gcodes"), ("print", "true" if start else "false")]
        pre = b"".join(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{k}"\r\n\r\n{v}\r\n'.encode()
            for k, v in fields
        )
        fname = r["file"].replace('"', "_")
        pre += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{fname}"\r\n'
                f"Content-Type: application/octet-stream\r\n\r\n").encode("utf-8")
        post = f"\r\n--{boundary}--\r\n".encode()
        conn = http.client.HTTPConnection(u.hostname, u.port or 80, timeout=300)
        try:
            conn.putrequest("POST", "/server/files/upload")
            conn.putheader("Content-Type", f"multipart/form-data; boundary={boundary}")
            conn.putheader("Content-Length", str(len(pre) + path.stat().st_size + len(post)))
            conn.endheaders()
            conn.send(pre)
            with open(path, "rb") as f:
                while chunk := f.read(1 << 16):
                    conn.send(chunk)
            conn.send(post)
            resp = conn.getresponse()
            body = resp.read().decode(errors="replace")
        finally:
            conn.close()
        if resp.status >= 300:
            raise RuntimeError(f"Moonraker replied {resp.status}: {body[:200]}")
        data = json.loads(body) if body else {}
        with self.lock:
            r.update(uploaded=True, upload_error=None,
                     printing=bool(r.get("printing") or data.get("print_started") or start))
            self._save_job(j)
