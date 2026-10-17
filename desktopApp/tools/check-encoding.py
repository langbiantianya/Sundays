#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""源码编码体检：U+FFFD 替换字符 / NUL 字节 / UTF-8 BOM / 夹在源码里的 BOM 字符。

这三样东西在本仓库都被真实咬过：

- **U+FFFD** —— 批量写入中文时解码失败留下的占位符。原字符已经丢失、git 历史里
  也未必找得回（`git log --follow` 逐版本核过，最早可追溯的提交同样损坏），最后
  只能靠上下文语义还原。它还是「静默」的：文件照常编译，只是注释里的话不通。
- **NUL 字节** —— 本是合法的字符字面量（`contains('<NUL>')` 拦截 null 字节），
  但裸 NUL 会让 git 把整个文件判成二进制，diff 不显示、grep 失效。Kotlin 里应写
  转义序列 `'\u0000'`。
- **UTF-8 BOM** —— 仓库约定一律无 BOM；Kotlin 编译器与 jpackage 都可能因此出怪问题。
- **夹在源码里的 U+FEFF** —— 与上一条是两回事：不是文件开头的 BOM，而是**文件中间**
  某个字符里藏着一个真的 U+FEFF 字符（本仓库的 CSV 导出就靠它写 BOM 头）。
  这一个被 ktlintFormat 实测咬过：它会把这个不可见字符从字符串字面量里静默删掉，
  `write("<U+FEFF>")` 变成 `write("")`——代码、注释、KDoc 全都原样，编译照过、
  测试照过，只有真拿 Excel 打开导出的 CSV 才看得出中文全成了乱码。
  正确写法是转义序列 `\uFEFF`，对 ktlint 而言就是普通 ASCII。

用法：python desktopApp/tools/check-encoding.py [--strict-nul]
退出码非 0 表示有问题。
"""
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
BAD = b"\xef\xbf\xbd"          # U+FFFD 的 UTF-8 编码
NUL = b"\x00"
BOM = b"\xef\xbb\xbf"
SHELL_OK_NUL = False           # .sh 里不允许裸 NUL；Kotlin 里允许 \u0000 转义但不允许裸字节

SUFFIXES = (".kt", ".kts", ".proto", ".yml", ".sh", ".ps1", ".properties",
            ".toml", ".xml", ".desktop", ".md", ".py", ".gitignore", ".gitattributes")


def tracked():
    out = subprocess.run(["git", "ls-files", "-z"], cwd=str(REPO),
                         capture_output=True, check=True).stdout
    return [p.decode("utf-8") for p in out.split(b"\0") if p]


def main():
    strict_nul = "--strict-nul" in sys.argv
    bad_char, bad_nul, bad_bom = [], [], []
    bad_inline_bom = []
    checked = 0

    for rel in tracked():
        p = REPO / rel
        name = p.name
        if not (name.endswith(SUFFIXES) or name in (".gitignore", ".gitattributes")):
            continue
        if not p.exists():
            continue
        checked += 1
        data = p.read_bytes()

        if BAD in data:
            text = data.decode("utf-8", errors="replace")
            for i, line in enumerate(text.splitlines(), 1):
                if "\ufffd" in line:
                    bad_char.append((rel, i, line.strip()[:100]))
        if NUL in data and (strict_nul or name.endswith(".sh")):
            bad_nul.append((rel, data.count(NUL)))
        if data.startswith(BOM):
            bad_bom.append(rel)

        # 去掉文件开头那个 BOM 之后，剩下的每一处 U+FEFF 都是「藏在源码里的不可见
        # 字符」。它们在编辑器里看不见、在 review 里看不出来，却会被 ktlintFormat
        # 悄悄删掉，从而改变字符串字面量的实际内容。必须当成错误报出来。
        rest = data[len(BOM):] if data.startswith(BOM) else data
        if BOM in rest:
            text = rest.decode("utf-8", errors="replace")
            for i, line in enumerate(text.splitlines(), 1):
                if "\ufeff" in line:
                    bad_inline_bom.append((rel, i, line.strip()[:100]))

    print("=" * 72)
    print("源码编码体检：检查 {} 个文本文件".format(checked))
    print("=" * 72)

    if bad_char:
        print("\n[U+FFFD 替换字符] {} 处 —— 这些位置的字符已损坏，需按上下文还原".format(len(bad_char)))
        for rel, ln, line in bad_char:
            print("  {}:{}  {}".format(rel, ln, line))
    else:
        print("\n[OK] 无 U+FFFD 替换字符")

    if bad_nul:
        print("\n[NUL 字节] {} 个文件 —— git 会把这些文件判成二进制".format(len(bad_nul)))
        for rel, n in bad_nul:
            print("  {}  ({} 个)".format(rel, n))
    else:
        print("[OK] 无裸 NUL 字节" + ("（Kotlin 内允许 \\u0000 转义）" if not strict_nul else ""))

    if bad_bom:
        print("\n[UTF-8 BOM] {} 个文件 —— 仓库约定一律无 BOM".format(len(bad_bom)))
        for rel in bad_bom:
            print("  " + rel)
    else:
        print("[OK] 无 UTF-8 BOM")

    if bad_inline_bom:
        print("\n[源码里夹着的 U+FEFF] {} 处 —— ktlintFormat 会把它从字符串里静默删掉".format(len(bad_inline_bom)))
        print("  后果：`write(\"<U+FEFF>\")` 变成 `write(\"\")`，编译与测试都发现不了，")
        print("  只有真拿 Excel 打开导出的 CSV 才看得出中文成了乱码。请改用转义 \\uFEFF。")
        for rel, ln, line in bad_inline_bom:
            print("  {}:{}  {}".format(rel, ln, line))
    else:
        print("[OK] 源码里没有夹带的 U+FEFF 不可见字符")

    total = len(bad_char) + len(bad_nul) + len(bad_bom) + len(bad_inline_bom)
    print("\n问题总数 = {}".format(total))
    return 1 if total else 0


if __name__ == "__main__":
    sys.exit(main())
