"""Миниатюры для G-code: программный рендер сетки (Orca CLI без OpenGL их не рисует).

Экран K2, Moonraker/Fluidd и приложение берут превью из блоков
'; thumbnail begin WxH LEN' … '; thumbnail end' в начале G-code.
"""
import array
import base64
import math
import struct
import zlib
from pathlib import Path

from preview import _decimate

THUMB_SIZES = ((300, 300), (96, 96))


def _png(w: int, h: int, rgba: bytearray) -> bytes:
    raw = b"".join(b"\x00" + bytes(rgba[r * w * 4:(r + 1) * w * 4]) for r in range(h))

    def chunk(t, d):
        return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def _hex_color(c: str | None) -> tuple[int, int, int]:
    try:
        c = (c or "").lstrip("#")[:6]
        return int(c[0:2], 16), int(c[2:4], 16), int(c[4:6], 16)
    except (ValueError, IndexError):
        return 255, 138, 61


def render(tris: array.array, size: int, color: str | None = None) -> bytes:
    """Вид спереди-сверху (как камера Orca по умолчанию), плоское освещение, прозрачный фон."""
    if len(tris) // 9 > 60000:
        tris = _decimate(tris, 60000)
    n = len(tris) // 9
    w = h = size
    rgba = bytearray(w * h * 4)
    if n == 0:
        return _png(w, h, rgba)
    xs, ys, zs = tris[0::3], tris[1::3], tris[2::3]
    cx, cy, cz = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2, (min(zs) + max(zs)) / 2
    yaw, pitch = math.radians(-35), math.radians(-60)
    cyaw, syaw, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)

    def view(x, y, z):
        x, y, z = x - cx, y - cy, z - cz
        x, y = x * cyaw - y * syaw, x * syaw + y * cyaw          # поворот вокруг Z
        y, z = y * cp - z * sp, y * sp + z * cp                  # наклон
        return x, z, -y                                          # экран X, экран Y (вверх), глубина

    pts = [view(tris[i], tris[i + 1], tris[i + 2]) for i in range(0, len(tris), 3)]
    sx = [p[0] for p in pts]
    sy = [p[1] for p in pts]
    span = max(max(sx) - min(sx), max(sy) - min(sy)) or 1.0
    scale = (size * 0.9) / span
    ox = size / 2 - (max(sx) + min(sx)) / 2 * scale
    oy = size / 2 + (max(sy) + min(sy)) / 2 * scale
    zbuf = array.array("f", [math.inf]) * (w * h)
    base = _hex_color(color)
    # Свет в пространстве вида: справа-сверху и со стороны камеры (глубина к камере отрицательна)
    lx, ly, lz = 0.35, 0.6, -0.72

    for t in range(n):
        (x0, y0, d0), (x1, y1, d1), (x2, y2, d2) = pts[t * 3], pts[t * 3 + 1], pts[t * 3 + 2]
        # нормаль в пространстве вида
        ux, uy, uz = x1 - x0, y1 - y0, d1 - d0
        vx, vy, vz = x2 - x0, y2 - y0, d2 - d0
        nx, ny, nz = uy * vz - uz * vy, uz * vx - ux * vz, ux * vy - uy * vx
        ln = math.sqrt(nx * nx + ny * ny + nz * nz) or 1.0
        nx, ny, nz = nx / ln, ny / ln, nz / ln
        if nz > 0:
            nx, ny, nz = -nx, -ny, -nz
        shade = 0.3 + 0.7 * max(0.0, nx * lx + ny * ly + nz * lz)
        col = (int(base[0] * shade), int(base[1] * shade), int(base[2] * shade))
        X0, Y0 = ox + x0 * scale, oy - y0 * scale
        X1, Y1 = ox + x1 * scale, oy - y1 * scale
        X2, Y2 = ox + x2 * scale, oy - y2 * scale
        minx, maxx = max(0, int(min(X0, X1, X2))), min(w - 1, int(max(X0, X1, X2)) + 1)
        miny, maxy = max(0, int(min(Y0, Y1, Y2))), min(h - 1, int(max(Y0, Y1, Y2)) + 1)
        area = (X1 - X0) * (Y2 - Y0) - (X2 - X0) * (Y1 - Y0)
        if abs(area) < 1e-9:
            continue
        inv = 1.0 / area
        for py in range(miny, maxy + 1):
            fy = py + 0.5
            for px in range(minx, maxx + 1):
                fx = px + 0.5
                a = ((X1 - fx) * (Y2 - fy) - (X2 - fx) * (Y1 - fy)) * inv
                b = ((X2 - fx) * (Y0 - fy) - (X0 - fx) * (Y2 - fy)) * inv
                c = 1.0 - a - b
                if a < -1e-6 or b < -1e-6 or c < -1e-6:
                    continue
                d = a * d0 + b * d1 + c * d2
                k = py * w + px
                if d < zbuf[k]:
                    zbuf[k] = d
                    rgba[k * 4:k * 4 + 4] = bytes((col[0], col[1], col[2], 255))
    return _png(w, h, rgba)


def inject(gcode: Path, tris: array.array, color: str | None = None):
    """Вставляет блоки миниатюр после HEADER_BLOCK_END (как делает Orca GUI)."""
    blocks = []
    for w, h in THUMB_SIZES:
        b64 = base64.b64encode(render(tris, w, color)).decode()
        lines = [b64[i:i + 78] for i in range(0, len(b64), 78)]
        blocks.append("; THUMBNAIL_BLOCK_START\n\n;\n"
                      f"; thumbnail begin {w}x{h} {len(b64)}\n"
                      + "".join(f"; {ln}\n" for ln in lines)
                      + "; thumbnail end\n; THUMBNAIL_BLOCK_END\n\n")
    text = "".join(blocks)
    tmp = gcode.with_suffix(".tmp")
    inserted = False
    with open(gcode, "r", errors="surrogateescape") as src, open(tmp, "w", errors="surrogateescape") as dst:
        for i, line in enumerate(src):
            dst.write(line)
            if not inserted and line.startswith("; HEADER_BLOCK_END"):
                dst.write("\n" + text)
                inserted = True
            elif not inserted and i > 50:
                break
        if inserted:
            while chunk := src.read(1 << 20):
                dst.write(chunk)
    if inserted:
        tmp.replace(gcode)
    else:
        tmp.unlink(missing_ok=True)
    return inserted
