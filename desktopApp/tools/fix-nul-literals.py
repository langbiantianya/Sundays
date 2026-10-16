#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把字符字面量里的裸 NUL 字节换成等价的 Kotlin 转义序列。

背景：本仓库多处用 `contains('<NUL>')` 拦截 null 字节（注释写明「防 SQL 截断」）。
裸 NUL 是**有意为之**，但它让 git 把整个文件判成二进制 —— 后果是 diff 不显示、
grep 失效，新加的声明头和任何后续改动都完全无法 review。

`'<NUL>'` 与 `'\u0000'` 在 Kotlin 里是同一个字符字面量，编译器产物逐字节相同。
本脚本只替换「单引号 + NUL + 单引号」这一种形态，不碰其他 NUL。

替换后做往返校验：把 \\u0000 还原成裸 NUL，字节流必须与替换前完全一致；
同时核对行尾未被改动。

用法：python desktopApp/tools/fix-nul-literals.py [--apply]
默认 dry-run，加 --apply 才真正写盘。幂等 —— 没有裸 NUL 时不做任何事。
"""
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
NUL = b"\x00"
PATTERN = b"'" + NUL + b"'"          # '<NUL>'
REPLACEMENT = b"'\\u0000'"            # '\u0000'
SUFFIXES = (".kt", ".kts")


def tracked():
    out = subprocess.run(["git", "ls-files", "-z"], cwd=str(REPO),
                         capture_output=True, check=True).stdout
    return [p.decode("utf-8") for p in out.split(b"\0") if p]


def main():
    apply = "--apply" in sys.argv
    hits, changed = 0, []

    for rel in tracked():
        if not rel.endswith(SUFFIXES):
            continue
        p = REPO / rel
        if not p.exists():
            continue
        before = p.read_bytes()
        n = before.count(PATTERN)
        if n == 0:
            continue
        hits += n
        after = before.replace(PATTERN, REPLACEMENT)

        if after.replace(REPLACEMENT, PATTERN) != before:
            print("  [FAIL] {}  往返校验不一致，跳过".format(rel))
            return 1
        if after.count(b"\r\n") != before.count(b"\r\n"):
            print("  [FAIL] {}  行尾会变，跳过".format(rel))
            return 1

        changed.append((rel, n, len(before), len(after)))
        if apply:
            p.write_bytes(after)

    print("=" * 72)
    print("[{}] 裸 NUL 字符字面量：{} 处，涉及 {} 个文件".format(
        "APPLY" if apply else "DRY-RUN", hits, len(changed)))
    print("=" * 72)
    for rel, n, b0, b1 in changed:
        print("  {}  {} 处  ({} -> {} 字节)".format(rel, n, b0, b1))
    if hits == 0:
        print("  [OK] 没有需要替换的裸 NUL 字符字面量")
    return 0


if __name__ == "__main__":
    sys.exit(main())
