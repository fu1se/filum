"""Данные для 3D-предпросмотра на телефоне.

mesh_bytes()  — сетка модели: "K2M1", uint32 треугольников, float32 bbox[6], затем
                float32 x,y,z × 3 вершины на треугольник (без индексов).
toolpath_bytes() — траектории экструзии из G-code: "K2G1", uint32 слоёв, uint32 отрезков,
                float32 bbox[6], float32 z[слоёв], uint32 конец_слоя[слоёв] (накопительно),
                int16 x0,y0,x1,y1 × отрезков (единица 0.02 мм), uint8 (тип | инструмент << 4) × отрезков.
Всё little-endian; ответ отдаётся сжатым gzip.
"""
import array
import gzip
import threading
import math
import struct
import subprocess
import os
from pathlib import Path

MAX_TRIANGLES = 300_000
UNIT = 0.02  # мм на единицу int16: ±655 мм

# Типы линий Orca → код (цвет выбирает телефон)
FEATURES = {
    "outer wall": 1, "external perimeter": 1,
    "inner wall": 2, "perimeter": 2,
    "overhang wall": 3, "overhang perimeter": 3,
    "sparse infill": 4, "internal infill": 4,
    "internal solid infill": 5, "solid infill": 5,
    "top surface": 6, "top solid infill": 6,
    "bottom surface": 7,
    "bridge": 8, "internal bridge": 8, "bridge infill": 8,
    "gap infill": 9,
    "support": 10, "support material": 10,
    "support interface": 11, "support material interface": 11, "support transition": 11,
    "brim": 12, "skirt": 12, "skirt/brim": 12,
    "ironing": 13,
    "prime tower": 14, "wipe tower": 14,
}


# ---------------------------------------------------------------- сетка

def _read_stl(path: Path) -> array.array:
    data = path.read_bytes()
    if len(data) >= 84:
        n = struct.unpack_from("<I", data, 80)[0]
        if 84 + n * 50 == len(data):
            out = array.array("f")
            for i in range(n):
                out.extend(struct.unpack_from("<9f", data, 84 + i * 50 + 12))
            return out
    # ASCII STL
    out = array.array("f")
    for line in data.decode(errors="replace").splitlines():
        line = line.strip()
        if line.startswith("vertex"):
            out.extend(float(v) for v in line.split()[1:4])
    return out


def _decimate(tris: array.array, target: int) -> array.array:
    """Кластеризация вершин по сетке: грубо, но быстро и без numpy."""
    n = len(tris) // 9
    xs, ys, zs = tris[0::3], tris[1::3], tris[2::3]
    lo = (min(xs), min(ys), min(zs))
    hi = (max(xs), max(ys), max(zs))
    size = max(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]) or 1.0
    cell = size / max(8, int(math.sqrt(target) * 1.2))
    while True:
        out = array.array("f")
        for i in range(n):
            b = i * 9
            keys = []
            for k in range(3):
                x, y, z = tris[b + k * 3], tris[b + k * 3 + 1], tris[b + k * 3 + 2]
                keys.append((int((x - lo[0]) / cell), int((y - lo[1]) / cell), int((z - lo[2]) / cell)))
            if keys[0] == keys[1] or keys[1] == keys[2] or keys[0] == keys[2]:
                continue  # треугольник схлопнулся
            for kx, ky, kz in keys:
                out.extend(((kx + .5) * cell + lo[0], (ky + .5) * cell + lo[1], (kz + .5) * cell + lo[2]))
        if len(out) // 9 <= target * 1.3 or cell > size / 8:
            return out
        cell *= 1.4


def mesh_bytes(model_path: Path, orca: Path, workdir: Path) -> bytes:
    if model_path.suffix.lower() == ".stl":
        tris = _read_stl(model_path)
    else:
        out = workdir / "mesh_export"
        out.mkdir(parents=True, exist_ok=True)
        src = workdir / ("mesh_src" + model_path.suffix.lower())  # Orca и не-ASCII пути
        if not src.exists():
            try:
                os.link(model_path, src)
            except OSError:
                src.write_bytes(model_path.read_bytes())
        p = subprocess.run([str(orca), "--export-stl", "--outputdir", str(out), str(src)],
                           capture_output=True, text=True, timeout=300,
                           env=dict(os.environ, HOME=str(workdir)))
        tris = array.array("f")
        for f in sorted(out.rglob("*.stl")):
            tris.extend(_read_stl(f))
        if not tris:
            raise RuntimeError("OrcaSlicer could not export the mesh: " + (p.stdout + p.stderr)[-300:])
    if len(tris) // 9 > MAX_TRIANGLES:
        tris = _decimate(tris, MAX_TRIANGLES)
    xs, ys, zs = tris[0::3], tris[1::3], tris[2::3]
    head = b"K2M1" + struct.pack("<I6f", len(tris) // 9, min(xs), min(ys), min(zs), max(xs), max(ys), max(zs))
    return head + tris.tobytes()


_mesh_locks: dict[str, threading.Lock] = {}


def cached_mesh_gz(model_path: Path, orca: Path) -> bytes:
    """Сетка модели (сжатая), считается один раз и лежит рядом с моделью."""
    cache = model_path.parent / "mesh.bin.gz"
    if not cache.exists():
        with _mesh_locks.setdefault(str(cache), threading.Lock()):
            if not cache.exists():
                tmp = cache.with_suffix(".tmp")
                tmp.write_bytes(gzip.compress(mesh_bytes(model_path, orca, model_path.parent), 5))
                tmp.replace(cache)
    return cache.read_bytes()


def mesh_tris(model_path: Path, orca: Path) -> array.array:
    raw = gzip.decompress(cached_mesh_gz(model_path, orca))
    tris = array.array("f")
    tris.frombytes(raw[32:])
    return tris


# ---------------------------------------------------------------- траектории

def _params(parts):
    out = {}
    for p in parts[1:]:
        if p and p[0] in "XYZEIJR":
            try:
                out[p[0]] = float(p[1:])
            except ValueError:
                pass
    return out


def toolpath_bytes(gcode_path: Path) -> bytes:
    seg = array.array("h")        # x0,y0,x1,y1
    typ = array.array("B")
    layer_z: list[float] = []
    layer_end: list[int] = []

    abs_xyz, rel_e = True, False
    # Пока X/Y не заданы явно, позиция неизвестна — не рисуем «линию из нуля»
    x = y = math.nan
    z = e = 0.0
    feature = 0
    tool = 0
    cur_layer_z = None
    # Упрощение: продолжаем отрезок, если новая точка почти на той же прямой
    run_start = run_end = None
    run_type = -1
    run_tool = 0
    bbox = [math.inf, math.inf, math.inf, -math.inf, -math.inf, -math.inf]

    def flush():
        nonlocal run_start, run_end
        if run_start is not None:
            seg.extend((int(round(run_start[0] / UNIT)), int(round(run_start[1] / UNIT)),
                        int(round(run_end[0] / UNIT)), int(round(run_end[1] / UNIT))))
            typ.append(run_type | (min(run_tool, 15) << 4))
        run_start = run_end = None

    def add(x0, y0, x1, y1):
        nonlocal run_start, run_end, run_type, cur_layer_z, run_tool
        if cur_layer_z is None or abs(z - cur_layer_z) > 1e-4:
            flush()
            if layer_z:
                layer_end.append(len(typ))
            layer_z.append(z)
            cur_layer_z = z
        if run_start is not None and run_type == feature and run_tool == tool and run_end == (x0, y0):
            ax, ay = run_start
            dx, dy = x1 - ax, y1 - ay
            ln = math.hypot(dx, dy)
            if ln > 0 and abs((run_end[0] - ax) * dy - (run_end[1] - ay) * dx) / ln < 0.02 \
                    and (run_end[0] - ax) * dx + (run_end[1] - ay) * dy > 0:
                run_end = (x1, y1)
                return
        flush()
        run_start, run_end, run_type, run_tool = (x0, y0), (x1, y1), feature, tool
        if x1 < bbox[0]: bbox[0] = x1
        if y1 < bbox[1]: bbox[1] = y1
        if x1 > bbox[3]: bbox[3] = x1
        if y1 > bbox[4]: bbox[4] = y1
        if x0 < bbox[0]: bbox[0] = x0
        if y0 < bbox[1]: bbox[1] = y0
        if x0 > bbox[3]: bbox[3] = x0
        if y0 > bbox[4]: bbox[4] = y0

    with open(gcode_path, "r", errors="replace") as f:
        for line in f:
            c = line[0] if line else ""
            if c == ";":
                if line.startswith(";TYPE:"):
                    feature = FEATURES.get(line[6:].strip().lower(), 0)
                continue
            if c == "T" and line[1:2].isdigit():
                try:
                    tool = int(line[1:].split()[0])
                except ValueError:
                    pass
                continue
            if c not in "GM":
                continue
            line = line.split(";", 1)[0]
            parts = line.split()
            if not parts:
                continue
            cmd = parts[0]
            if cmd in ("G1", "G0", "G2", "G3"):
                p = _params(parts)
                nx, ny, nz = x, y, z
                if abs_xyz:
                    nx, ny, nz = p.get("X", x), p.get("Y", y), p.get("Z", z)
                else:
                    nx, ny, nz = x + p.get("X", 0.0), y + p.get("Y", 0.0), z + p.get("Z", 0.0)
                extruding = False
                if "E" in p:
                    de = p["E"] if rel_e else p["E"] - e
                    extruding = de > 1e-6
                    e = e + p["E"] if rel_e else p["E"]
                z = nz
                if extruding and (nx != x or ny != y) and not (math.isnan(x) or math.isnan(y)
                                                                or math.isnan(nx) or math.isnan(ny)):
                    if cmd in ("G2", "G3") and ("I" in p or "J" in p):
                        cx, cy = x + p.get("I", 0.0), y + p.get("J", 0.0)
                        r = math.hypot(x - cx, y - cy)
                        a0 = math.atan2(y - cy, x - cx)
                        a1 = math.atan2(ny - cy, nx - cx)
                        sweep = a1 - a0
                        if cmd == "G3" and sweep <= 0:
                            sweep += 2 * math.pi
                        if cmd == "G2" and sweep >= 0:
                            sweep -= 2 * math.pi
                        steps = max(2, min(64, int(abs(sweep) * r / 0.6) + 1))
                        px, py = x, y
                        for k in range(1, steps + 1):
                            if k == steps:
                                qx, qy = nx, ny
                            else:
                                a = a0 + sweep * k / steps
                                qx, qy = cx + r * math.cos(a), cy + r * math.sin(a)
                            add(px, py, qx, qy)
                            px, py = qx, qy
                    else:
                        add(x, y, nx, ny)
                    if z < bbox[2]: bbox[2] = z
                    if z > bbox[5]: bbox[5] = z
                x, y = nx, ny
            elif cmd == "G90":
                abs_xyz = True
            elif cmd == "G91":
                abs_xyz = False
            elif cmd == "M83":
                rel_e = True
            elif cmd == "M82":
                rel_e = False
            elif cmd == "G92":
                p = _params(parts)
                if "E" in p:
                    e = p["E"]
                if "X" in p: x = p["X"]
                if "Y" in p: y = p["Y"]
                if "Z" in p: z = p["Z"]
    flush()
    if layer_z:
        layer_end.append(len(typ))
    if not typ:
        bbox = [0.0] * 6
    head = b"K2G1" + struct.pack("<II6f", len(layer_z), len(typ), *bbox)
    return (head + array.array("f", layer_z).tobytes() + array.array("I", layer_end).tobytes()
            + seg.tobytes() + typ.tobytes())
