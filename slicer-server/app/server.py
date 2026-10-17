#!/usr/bin/env python3
"""K2 Slicer — облачный слайсер OrcaSlicer для Creality K2 Plus.

Только стандартная библиотека Python. Пресеты: системные из OrcaSlicer +
пользовательские с orca-sync. Модели: загрузка файла или MakerWorld.
Готовый G-code отправляется в Moonraker принтера.
"""
import json
import os
import re
import signal
import sys
import threading
import time
import traceback
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from makerworld import MakerWorld, MakerWorldError
from orcasync import OrcaSync
from presets import PresetIndex, parse_vector
import base64
import gzip
import preview
import cfs
from slicer import Slicer, moonraker_url

DATA = Path(os.environ.get("SLICER_DATA", "/data"))
ORCA_DIR = Path(os.environ.get("ORCA_DIR", "/data/orca/current"))
PORT = int(os.environ.get("SLICER_PORT", "8090"))
# Необязательно: приложение передаёт адрес принтера в запросах (printer_host / ?host=)
MOONRAKER = os.environ.get("MOONRAKER_URL", "")
PRINTER_FILTER = os.environ.get("PRINTER_FILTER", "Creality")
DEFAULTS = {
    "printer": os.environ.get("DEFAULT_PRINTER", ""),
    "process": os.environ.get("DEFAULT_PROCESS", ""),
    "filament": os.environ.get("DEFAULT_FILAMENT", ""),
}
SYNC_REFRESH = int(os.environ.get("ORCA_SYNC_REFRESH", "600"))
STATIC = Path(__file__).parent / "static"
PREVIEW_LOCKS: dict[str, threading.Lock] = {}
MAX_UPLOAD = 512 * 1024 * 1024

DATA.mkdir(parents=True, exist_ok=True)


class State:
    def __init__(self):
        self.index = PresetIndex(ORCA_DIR / "resources" / "profiles")
        self.sync = OrcaSync(os.environ.get("ORCA_SYNC_URL", ""), os.environ.get("ORCA_SYNC_USER", ""),
                             os.environ.get("ORCA_SYNC_PASSWORD", ""), DATA / "orcasync.json")
        self.sync_status = {"ok": None, "configured": True, "error": None, "count": 0, "at": None}
        self.sync_lock = threading.Lock()
        self.mw = MakerWorld(DATA / "makerworld.json")
        self.slicer = Slicer(DATA, ORCA_DIR / "AppRun", lambda: self.index, MOONRAKER)
        self.refresh_sync()
        threading.Thread(target=self._sync_loop, daemon=True).start()

    def refresh_sync(self):
        if not self.sync.configured:
            self.sync_status = {"ok": False, "configured": False, "error": "orca-sync is not configured", "count": 0, "at": time.time()}
            return
        with self.sync_lock:
            try:
                profiles = self.sync.pull()
                self.index.set_user_presets(profiles)
                self.sync_status = {"ok": True, "configured": True, "error": None, "count": len(profiles), "at": time.time()}
            except Exception as e:
                # Старые пользовательские пресеты остаются в индексе
                self.sync_status = {"ok": False, "configured": True, "error": str(e), "count": self.sync_status["count"], "at": time.time()}

    def _sync_loop(self):
        while True:
            time.sleep(SYNC_REFRESH)
            self.refresh_sync()

    def defaults(self, printers, processes, filaments) -> dict:
        def pick(want, items):
            names = [i["name"] for i in items]
            if want in names:
                return want
            user = [i["name"] for i in items if i["user"]]
            return (user or names or [None])[0]
        return {"printer": pick(DEFAULTS["printer"], printers),
                "process": pick(DEFAULTS["process"], processes),
                "filament": pick(DEFAULTS["filament"], filaments)}


S: State = None  # type: ignore


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "K2Slicer/1.0"

    def log_message(self, fmt, *args):
        if "/api/jobs/" in (args[0] if args else ""):
            return  # опрос статуса не засоряет лог
        super().log_message(fmt, *args)

    # ---------- ответы ----------

    def send(self, code: int, obj=None, ctype="application/json; charset=utf-8", headers=None):
        body = obj if isinstance(obj, bytes) else (b"" if obj is None else json.dumps(obj, ensure_ascii=False).encode())
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def send_cached_gz(self, cache: Path, build):
        """Двоичные данные предпросмотра: строим один раз, храним и отдаём сжатыми."""
        if not cache.exists():
            lock = PREVIEW_LOCKS.setdefault(str(cache), threading.Lock())
            with lock:
                if not cache.exists():
                    tmp = cache.with_suffix(".tmp")
                    tmp.write_bytes(gzip.compress(build(), 5))
                    tmp.replace(cache)
        data = cache.read_bytes()
        self.send(200, data, "application/octet-stream",
                  {"Content-Encoding": "gzip", "Cache-Control": "max-age=86400"})

    def send_file(self, path: Path, ctype: str, download_name: str | None = None):
        size = path.stat().st_size
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(size))
        self.send_header("Access-Control-Allow-Origin", "*")
        if download_name:
            q = urllib.parse.quote(download_name)
            self.send_header("Content-Disposition", f"attachment; filename*=UTF-8''{q}")
        self.end_headers()
        with open(path, "rb") as f:
            while chunk := f.read(1 << 16):
                self.wfile.write(chunk)

    def json_body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if n > 1 << 20:
            raise ValueError("request too large")
        data = json.loads(self.rfile.read(n) or b"{}")
        if not isinstance(data, dict):
            raise ValueError("a JSON object was expected")
        return data

    # ---------- маршрутизация ----------

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        self.route("GET")

    def do_HEAD(self):
        self.route("GET")

    def do_POST(self):
        self.route("POST")

    def route(self, method: str):
        raw = self.path
        try:  # http.server читает строку запроса как latin-1; сырой UTF-8 возвращаем как было
            raw = raw.encode("latin-1").decode("utf-8")
        except UnicodeError:
            pass
        u = urllib.parse.urlsplit(raw)
        self.q = {k: v[0] for k, v in urllib.parse.parse_qs(u.query).items()}
        path = u.path.rstrip("/") or "/"
        for (m, pattern), fn in ROUTES:
            if m != method:
                continue
            match = re.fullmatch(pattern, path)
            if match:
                try:
                    return fn(self, *match.groups())
                except (KeyError, FileNotFoundError) as e:
                    return self.send(404, {"error": str(e).strip("'")})
                except (ValueError, MakerWorldError) as e:
                    return self.send(400, {"error": str(e)})
                except Exception as e:
                    traceback.print_exc()
                    return self.send(500, {"error": f"{type(e).__name__}: {e}"})
        self.send(404, {"error": "not found"})

    # ---------- страницы ----------

    def index(self):
        self.send(200, (STATIC / "index.html").read_bytes(), "text/html; charset=utf-8")

    def health(self):
        self.send(200, {"status": "ok", "orca": (ORCA_DIR / "AppRun").exists()})

    # ---------- пресеты ----------

    def presets(self):
        idx = S.index
        printers = idx.list("machine", name_filter=PRINTER_FILTER)
        printer = self.q.get("printer")
        if printer not in {p["name"] for p in printers}:
            # например, пользовательский принтер, пока orca-sync недоступен
            printer = S.defaults(printers, [], [])["printer"]
        processes = idx.list("process", printer) if printer else []
        filaments = idx.list("filament", printer) if printer else []
        bed, height = [350.0, 350.0], 350.0
        if printer:
            try:
                m = idx.resolve(printer)
                area = m.get("printable_area") or []
                if isinstance(area, str):
                    area = parse_vector(area)
                pts = [tuple(float(v) for v in a.split("x")) for a in area]
                if pts:
                    bed = [max(p[0] for p in pts) - min(p[0] for p in pts), max(p[1] for p in pts) - min(p[1] for p in pts)]
                h = m.get("printable_height")
                height = float(h[0] if isinstance(h, list) else h) if h else height
            except Exception:
                pass
        self.send(200, {
            "printers": printers, "processes": processes, "filaments": filaments,
            "printer": printer, "defaults": S.defaults(printers, processes, filaments),
            "sync": S.sync_status, "bed": bed, "bed_height": height,
        })

    def presets_refresh(self):
        S.refresh_sync()
        self.send(200, {"sync": S.sync_status})

    # ---------- модели ----------

    def upload_model(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_UPLOAD:
            raise ValueError("empty or too large file")
        name = self.q.get("name") or "model.stl"
        m = S.slicer.add_model(name, self.rfile, n, "upload")
        self.send(200, m)

    def list_models(self):
        self.send(200, {"models": S.slicer.models()})

    def model_thumb(self, mid):
        data = S.slicer.model_thumb(mid, int(self.q.get("plate", 1)))
        if not data:
            raise KeyError("no thumbnail")
        self.send(200, data, "image/png", {"Cache-Control": "max-age=86400"})

    def model_mesh(self, mid):
        _, path = S.slicer.model(mid)
        self.send(200, preview.cached_mesh_gz(path, ORCA_DIR / "AppRun"), "application/octet-stream",
                  {"Content-Encoding": "gzip", "Cache-Control": "max-age=86400"})

    def cfs_state(self):
        """Катушки CFS и подходящий пресет пластика под каждую."""
        url = moonraker_url(self.q.get("host"), MOONRAKER)
        if not url:
            raise ValueError("printer address is unknown: pass ?host= or set MOONRAKER_URL")
        st = cfs.state(url)
        printer = self.q.get("printer")
        if printer and printer in S.index.by_name:
            for s in st["slots"]:
                s["preset"] = cfs.suggest_preset(S.index, printer, s)
        self.send(200, st)

    # ---------- MakerWorld ----------

    def mw_status(self):
        self.send(200, S.mw.status())

    def mw_login(self):
        b = self.json_body()
        if b.get("token"):
            S.mw.set_token(b["token"], b.get("account"))
            return self.send(200, {"ok": True, **S.mw.status()})
        r = S.mw.login(b.get("account", "").strip(), b.get("password", ""), b.get("code", "").strip())
        self.send(200, {**r, **S.mw.status()})

    def mw_logout(self):
        S.mw.logout()
        self.send(200, S.mw.status())

    def mw_search(self):
        self.send(200, S.mw.search(self.q.get("q", ""), int(self.q.get("offset", 0))))

    def mw_design(self, ref):
        self.send(200, S.mw.design(S.mw.parse_design_id(urllib.parse.unquote(ref))))

    def mw_resolve(self):
        """Ссылка MakerWorld → id модели (и профиля, если есть #profileId-…)."""
        url = self.q.get("url", "")
        self.send(200, {"design_id": S.mw.parse_design_id(url), "profile_id": S.mw.parse_instance_id(url)})

    def mw_import(self):
        b = self.json_body()
        design_id = int(b["design_id"])
        instance_id = b.get("instance_id")
        d = S.mw.design(design_id)
        if not instance_id:
            instance_id = d.get("default_instance") or (d["instances"][0]["id"] if d["instances"] else None)
        if not instance_id:
            raise ValueError("the model has no print profiles")
        tmp = DATA / "tmp"
        tmp.mkdir(exist_ok=True)
        path = S.mw.download_3mf(int(instance_id), tmp)
        m = S.slicer.register_file(path, "makerworld", {
            "title": d.get("title"), "design_id": design_id, "instance_id": int(instance_id),
            "cover": d.get("cover"), "author": d.get("author"),
        })
        self.send(200, m)

    # ---------- нарезка ----------

    def slice(self):
        b = self.json_body()
        if b.get("filament") and not b.get("filaments"):
            b["filaments"] = [b["filament"]]
        self.send(200, S.slicer.submit(b))

    def jobs(self):
        self.send(200, {"jobs": S.slicer.list_jobs()})

    def job(self, jid):
        self.send(200, S.slicer.job(jid))

    def job_gcode(self, jid, plate):
        p = S.slicer.gcode_path(jid, int(plate))
        self.send_file(p, "text/x.gcode", p.name)

    def job_preview(self, jid, plate):
        p = S.slicer.gcode_path(jid, int(plate))
        self.send_cached_gz(p.parent / f".preview_{int(plate)}.bin.gz", lambda: preview.toolpath_bytes(p))

    def job_thumb(self, jid, plate):
        """Миниатюра, которую Orca встраивает в G-code (самая крупная)."""
        p = S.slicer.gcode_path(jid, int(plate))
        best, cur, size = None, None, 0
        with open(p, "r", errors="replace") as f:
            for i, line in enumerate(f):
                if i > 20000:
                    break
                if line.startswith("; thumbnail begin") or line.startswith("; thumbnail_PNG begin"):
                    m = re.search(r"(\d+)x(\d+)", line)
                    cur, size = [], int(m.group(1)) * int(m.group(2)) if m else 0
                elif line.startswith("; thumbnail end") or line.startswith("; thumbnail_PNG end"):
                    if cur is not None and (best is None or size > best[0]):
                        best = (size, "".join(cur))
                    cur = None
                elif cur is not None:
                    cur.append(line[1:].strip())
        if not best:
            raise KeyError("the G-code has no thumbnail")
        self.send(200, base64.b64decode(best[1]), "image/png", {"Cache-Control": "max-age=86400"})

    def job_upload(self, jid):
        b = self.json_body()
        self.send(200, S.slicer.upload(jid, int(b.get("plate", 1)), bool(b.get("start", False)), b.get("printer_host")))


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def handle_error(self, request, client_address):
        # Телефон часто закрывает соединение сам (свернули приложение) — это не ошибка
        if isinstance(sys.exc_info()[1], (ConnectionResetError, BrokenPipeError, TimeoutError)):
            return
        super().handle_error(request, client_address)


ROUTES = [
    (("GET", r"/"), Handler.index),
    (("GET", r"/health"), Handler.health),
    (("GET", r"/api/presets"), Handler.presets),
    (("POST", r"/api/presets/refresh"), Handler.presets_refresh),
    (("POST", r"/api/models"), Handler.upload_model),
    (("GET", r"/api/models"), Handler.list_models),
    (("GET", r"/api/cfs"), Handler.cfs_state),
    (("GET", r"/api/models/([0-9a-f]{12})/thumb"), Handler.model_thumb),
    (("GET", r"/api/models/([0-9a-f]{12})/mesh"), Handler.model_mesh),
    (("GET", r"/api/makerworld/status"), Handler.mw_status),
    (("POST", r"/api/makerworld/login"), Handler.mw_login),
    (("POST", r"/api/makerworld/logout"), Handler.mw_logout),
    (("GET", r"/api/makerworld/search"), Handler.mw_search),
    (("GET", r"/api/makerworld/resolve"), Handler.mw_resolve),
    (("GET", r"/api/makerworld/design/([^/]+)"), Handler.mw_design),
    (("POST", r"/api/makerworld/import"), Handler.mw_import),
    (("POST", r"/api/slice"), Handler.slice),
    (("GET", r"/api/jobs"), Handler.jobs),
    (("GET", r"/api/jobs/([0-9a-f]{12})"), Handler.job),
    (("GET", r"/api/jobs/([0-9a-f]{12})/gcode/(\d+)"), Handler.job_gcode),
    (("POST", r"/api/jobs/([0-9a-f]{12})/upload"), Handler.job_upload),
    (("GET", r"/api/jobs/([0-9a-f]{12})/preview/(\d+)"), Handler.job_preview),
    (("GET", r"/api/jobs/([0-9a-f]{12})/thumb/(\d+)"), Handler.job_thumb),
]


def main():
    global S
    # В контейнере сервер — PID 1, а ему SIGTERM по умолчанию не приходит: без
    # обработчика `docker stop` ждал бы таймаут и убивал процесс
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    if not (ORCA_DIR / "AppRun").exists():
        raise SystemExit(f"OrcaSlicer not found in {ORCA_DIR} (the Docker image or start.sh installs it)")
    S = State()
    print(f"Filum Slicer: http://0.0.0.0:{PORT}  orca={ORCA_DIR}  moonraker={MOONRAKER}  "
          f"presets={len(S.index.by_name)}  sync={S.sync_status}", flush=True)
    Server(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
