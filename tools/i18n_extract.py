#!/usr/bin/env python3
"""Находит в Kotlin-исходниках строковые литералы с кириллицей (вне комментариев).

Шаблоны Kotlin (${expr}, $name) превращаются в позиционные аргументы %1$s… —
так строки можно вынести в strings.xml. Вывод — JSON-список для перевода.
"""
import json, re, sys
from pathlib import Path

CYR = re.compile(r"[А-Яа-яЁё]")


def scan(src: str):
    """Литералы "…" верхнего уровня: (start, end, raw_body, parts). parts — текст и выражения."""
    i, n, out = 0, len(src), []
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            i = src.find("\n", i); i = n if i < 0 else i; continue
        if src.startswith("/*", i):
            j = src.find("*/", i + 2); i = n if j < 0 else j + 2; continue
        if src.startswith('"""', i):
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3; continue   # raw-строки (HTML/JS) не трогаем
        if c == "'":
            # символьный литерал: 'a', '\n', 'A'
            j = i + 1
            if j < n and src[j] == "\\": j += 2
            while j < n and src[j] != "'": j += 1
            i = j + 1; continue
        if c == '"':
            start = i; i += 1; parts = []; buf = []
            while i < n and src[i] != '"':
                if src[i] == "\\":
                    buf.append(src[i:i + 2]); i += 2; continue
                if src[i] == "$" and i + 1 < n and src[i + 1] == "{":
                    depth, j = 1, i + 2
                    while j < n and depth:
                        if src[j] == "{": depth += 1
                        elif src[j] == "}": depth -= 1
                        elif src[j] == '"':      # вложенная строка внутри выражения
                            k = j + 1
                            while k < n and src[k] != '"':
                                k += 2 if src[k] == "\\" else 1
                            j = k
                        j += 1
                    parts.append(("".join(buf), None)); buf = []
                    parts.append((None, src[i + 2:j - 1])); i = j; continue
                m = re.match(r"\$([A-Za-z_][A-Za-z0-9_]*)", src[i:])
                if m:
                    parts.append(("".join(buf), None)); buf = []
                    parts.append((None, m.group(1))); i += len(m.group(0)); continue
                buf.append(src[i]); i += 1
            parts.append(("".join(buf), None))
            out.append((start, i + 1, src[start + 1:i], parts))
            i += 1; continue
        i += 1
    return out


def to_format(parts):
    text, args = [], []
    for t, e in parts:
        if e is None:
            text.append(t)
        else:
            args.append(e); text.append(f"%{len(args)}$s")
    return "".join(text), args


if __name__ == "__main__":
    root = Path(sys.argv[1])
    items = []
    for f in sorted(root.rglob("*.kt")):
        src = f.read_text(encoding="utf-8")
        for start, end, body, parts in scan(src):
            if not CYR.search(body):
                continue
            nested = any(e and CYR.search(e) for _, e in parts)
            fmt, args = to_format(parts)
            line = src.count("\n", 0, start) + 1
            items.append({"file": str(f.relative_to(root)), "line": line, "ru": fmt, "args": args,
                          "nested": nested, "pct": "%" in "".join(t for t, e in parts if e is None)})
    json.dump(items, sys.stdout, ensure_ascii=False, indent=1)
