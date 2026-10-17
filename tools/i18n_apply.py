#!/usr/bin/env python3
"""Выносит строки с кириллицей из Kotlin в ресурсы Android.

  i18n_apply.py <java-root> <res-dir> <table.tsv>…

Таблица: «русский текст (как в коде, шаблоны → %1$s)» \t ключ \t английский перевод.
Литерал заменяется на tr(R.string.ключ, аргументы шаблона…); пишутся
values/strings.xml (английский — язык по умолчанию) и values-ru/strings.xml.
Строки без перевода в таблице не трогаются и выводятся списком.
"""
import re, sys
from pathlib import Path
from xml.sax.saxutils import escape
sys.path.insert(0, str(Path(__file__).parent))
from i18n_extract import scan, to_format, CYR

SKIP = {"[^a-z0-9а-я]+"}


def xml_text(s: str) -> str:
    s = escape(s).replace("'", "\\'").replace('"', '\\"')
    if s[:1] in "@?": s = "\\" + s
    return s


def ru_resource(fmt: str, has_args: bool) -> str:
    # \n в Kotlin — уже экранированная последовательность; в XML она тоже значит перевод строки
    if has_args:  # голый % (не %1$s) экранируем: строка пойдёт в String.format
        fmt = re.sub(r"%(?!\d+\$[sdf]|%|[.\d,]*[dfs])", "%%", fmt)
    return fmt


def load_tables(paths):
    table = {}
    for p in paths:
        for line in Path(p).read_text(encoding="utf-8").splitlines():
            if not line.strip(): continue
            ru, key, en = line.split("\t")
            table.setdefault(ru, (key, en))
    return table


def resources(res_dir: Path, entries: dict):
    """entries: key -> (ru, en, formatted). Дописывает к существующим strings.xml, сохраняя чужие строки."""
    for lang, idx in (("values", 1), ("values-ru", 0)):
        f = res_dir / lang / "strings.xml"
        f.parent.mkdir(parents=True, exist_ok=True)
        old = f.read_text(encoding="utf-8") if f.exists() else "<resources>\n</resources>\n"
        have = set(re.findall(r'<string name="([^"]+)"', old))
        lines = []
        for key, (ru, en, formatted) in entries.items():
            if key in have: continue
            attr = '' if formatted else ' formatted="false"'
            lines.append(f'    <string name="{key}"{attr}>{xml_text((ru, en)[idx])}</string>\n')
        f.write_text(old.replace("</resources>", "".join(lines) + "</resources>"), encoding="utf-8")


def main():
    root, res = Path(sys.argv[1]), Path(sys.argv[2])
    table = load_tables(sys.argv[3:])
    entries, missing = {}, []
    for f in sorted(root.rglob("*.kt")):
        src = f.read_text(encoding="utf-8")
        out, pos, changed = [], 0, False
        for start, end, body, parts in scan(src):
            if not CYR.search(body) or body in SKIP: continue
            fmt, args = to_format(parts)
            if fmt not in table:
                missing.append(f"{f.relative_to(root)}:{src.count(chr(10), 0, start) + 1}: {fmt}"); continue
            key, en = table[fmt]
            pct_own = bool(re.search(r"%[.\d,]*[dfs]", re.sub(r"%\d+\$s", "", fmt)))
            formatted = bool(args) or not ("%" in fmt)
            entries[key] = (ru_resource(fmt, bool(args)), en if args or not pct_own else en, formatted)
            call = "tr(R.string.%s%s)" % (key, "".join(", " + a for a in args))
            out.append(src[pos:start]); out.append(call); pos = end; changed = True
        if changed:
            out.append(src[pos:])
            new = "".join(out)
            pkg = re.search(r"^package ([\w.]+)", new, re.M).group(1)
            if pkg != "app.filum":
                for imp in ("app.filum.R", "app.filum.tr"):
                    if f"import {imp}\n" not in new:
                        new = re.sub(r"^(package [\w.]+\n)", r"\1\nimport %s" % imp, new, count=1, flags=re.M)
            f.write_text(new, encoding="utf-8")
    resources(res, entries)
    print(f"строк вынесено: {len(entries)}")
    for m in missing: print("нет перевода:", m)


if __name__ == "__main__":
    main()
