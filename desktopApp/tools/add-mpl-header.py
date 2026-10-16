#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# 
# Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>

# -*- coding: utf-8 -*-
"""
给仓库内所有可加注释的文本源文件注入 MPL 2.0 文件头。

设计要点（都是踩过坑才定下来的）：

1. **逐文件沿用原有行尾**，不统一转换。
   本仓库 CRLF 与 LF 并存且是有意义的：`.sh` / Flatpak manifest 用 LF（容器与
   Linux 工具链读取），其余用 CRLF（Windows 开发）。若统一按系统默认写回，
   会把 LF 文件改成 CRLF（或反之），diff 立刻炸成全文件重写。

2. **按字节读写，绝不做编码转换**。
   Windows 上用 `open(..., encoding=...)` 读再写会引入 BOM / 改变非法字节序列。
   这里全程用 `bytes`，只在最后拼头部时用 UTF-8 编码。

3. **跳过已有头的文件**（幂等），跳过不允许加注释的文件类型。
   - `.json`：JSON 规范无注释语法，加了就是语法错误（Flatpak manifest 尤其致命）。
   - `META-INF/services/*`：内容是纯类名列表。虽然 `ServiceLoader` 会忽略 `#`
     开头的行，但这类文件保持纯净是惯例，不动。
   - 二进制：`.jar` / `.png` / `.ico`。
   - `gradlew` / `gradlew.bat`：Gradle wrapper 自动生成，改了会在升级 wrapper 时冲突。

4. **跳过带 BOM 的文件**并在报告里列出 —— 头部写在 BOM 之后才是合法位置，
   与本工具的写入策略冲突，人工处理更稳妥。
"""

import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

AUTHOR = "langbiantianya"
EMAIL = "langbiantianya@outlook.com"
YEAR = "2026"

MPL_TEXT = (
    "This Source Code Form is subject to the terms of the Mozilla Public\n"
    "License, v. 2.0. If a copy of the MPL was not distributed with this\n"
    "file, You can obtain one at https://mozilla.org/MPL/2.0/.\n"
)
COPYRIGHT = "Copyright (c) {y} {a} <{e}>".format(y=YEAR, a=AUTHOR, e=EMAIL)

# 扩展名 -> (每行前缀, 整块包裹类型)
#   hash  : 逐行 `# `
#   slash : 逐行 `// `
#   block : `/* ... */` 每行 ` * `
#   xml   : `<!-- ... -->` 每行 `  `
#   ps    : `<# ... #>` 每行 `  `
STYLES = {
    ".kt": "block",
    ".kts": "block",
    ".proto": "slash",
    ".yml": "hash",
    ".sh": "hash",
    ".ps1": "ps",
    ".bat": "hash2",
    ".properties": "hash",
    ".toml": "hash",
    ".xml": "xml",
    ".desktop": "hash",
    ".gitignore": "hash",
    ".gitattributes": "hash",
    ".py": "hash",
}

BANNER = "This Source Code Form is subject to the terms of the Mozilla Public"


def wrap(style, lines):
    """按注释风格生成头部字节块（不含行尾，行尾由调用方决定）。"""
    if style == "block":
        out = ["/*"]
        for ln in lines:
            out.append((" * " + ln).rstrip())
        out.append(" */")
    elif style == "xml":
        out = ["<!--"]
        for ln in lines:
            out.append("  " + ln)
        out.append("-->")
    elif style == "ps":
        out = ["<#"]
        for ln in lines:
            out.append("  " + ln)
        out.append("#>")
    elif style == "slash":
        out = ["// " + ln for ln in lines]
    elif style == "hash":
        out = ["# " + ln for ln in lines]
    elif style == "hash2":
        # .bat 用 `::` 而非 `#`，因为 `#` 在部分上下文会被当标签
        out = [":: " + ln for ln in lines]
    else:
        raise ValueError(style)
    return "\n".join(out) + "\n"


def body_lines():
    return MPL_TEXT.rstrip("\n").split("\n") + ["", COPYRIGHT]


def git_ls_files():
    out = subprocess.run(
        ["git", "-C", str(REPO), "ls-files", "-z"],
        capture_output=True, check=True,
    ).stdout
    return [p.decode("utf-8") for p in out.split(b"\0") if p]


def detect_eol(data):
    """返回 (eol_bytes, had_bom)。文件为空时默认 LF。"""
    bom = data.startswith(b"\xef\xbb\xbf")
    body = data[3:] if bom else data
    if b"\r\n" in body:
        return b"\r\n", bom
    if b"\n" in body:
        return b"\n", bom
    return b"\n", bom


def split_shebang(body, eol):
    """拆出 shebang 行（若有）。

    内核**只认第一行**的 `#!` 来决定解释器。一旦把 shebang 挤到第二行，
    脚本在 Linux 上会直接 exec 失败 —— 而且本地 Windows 上完全看不出来，
    只有 CI runner 上才炸。所以头部必须插在 shebang 之后，不能盖在前面。
    """
    nl = b"\n" if eol == b"\n" else b"\r\n"
    if body.startswith(b"#!"):
        idx = body.find(nl)
        if idx == -1:
            return body, b""
        return body[:idx], body[idx + len(nl):]
    return b"", body


def header_bytes(style, eol):
    """生成该风格 + 该行尾下的头部字节块。

    故意做成**精确前缀**而不是「扫前几行找 MPL 声明」：block 风格的头部首行是
    `/*`，MPL 声明落在第 2 行，按内容猜边界会漏判并把头部再套一层。
    自己生成再比对，是唯一零歧义的做法，也顺便让幂等性变得可验证。
    """
    return wrap(style, body_lines()).replace("\n", eol.decode()).encode("utf-8")


def strip_header(rest, eol, hb):
    """若 rest 以头部块开头，剥离它。返回 (是否命中, 剩余正文)。"""
    if not rest.startswith(hb):
        return False, rest
    rest = rest[len(hb):]
    if rest.startswith(eol):
        rest = rest[len(eol):]
    return True, rest


def main():
    apply = "--apply" in sys.argv
    dry = not apply

    changed, skipped, fixed, bom_list, counts = [], [], [], [], {}

    for rel in git_ls_files():
        p = REPO / rel
        name = p.name
        ext = p.suffix.lower()

        # ---- 不该动的文件 ----
        if ext == ".json":
            continue
        if "META-INF/services" in rel.replace("\\", "/"):
            continue
        if ext in (".jar", ".png", ".ico", ".jpg", ".gif", ".webp"):
            continue
        if name in ("gradlew", "gradlew.bat", "LICENSE", "LICENSE.txt"):
            continue
        # 风格查找要同时试 suffix 和 文件名：`Path('.gitignore').suffix` 返回空串
        # （Python 认为点开头的名字是隐藏文件、没有扩展名），只查 suffix 会把
        # .gitignore / .gitattributes 这类仓库配置文件整个漏掉。
        style = STYLES.get(ext) or STYLES.get(name.lower())
        if style is None:
            continue

        data = p.read_bytes()
        eol, has_bom = detect_eol(data)
        if has_bom:
            bom_list.append(rel)

        body = data[3:] if has_bom else data
        if not body.strip():
            continue

        shebang, rest = split_shebang(body, eol)
        nl = b"\n" if eol == b"\n" else b"\r\n"
        hb = header_bytes(style, eol)

        had, rest = strip_header(rest, eol, hb)
        if had:
            # 头部已存在，只需判断它有没有被错置在 shebang **之前**。
            # 那种坏状态下首行是 `/*`，shebang 被挤进正文；而正常状态
            # （含「本无 shebang」的普通文件）剥掉头部后正文不会以 `#!` 开头。
            if rest.startswith(b"#!"):
                fixed.append(rel)
            else:
                skipped.append(rel)
                continue

        counts.setdefault(str(eol), []).append(rel)

        new = b""
        if shebang:
            new += shebang + nl
        new += hb
        if not rest.startswith((b"\n", b"\r\n")):
            new += nl
        new += rest

        changed.append(rel)
        if apply:
            p.write_bytes(new)

    tag = "DRY-RUN" if dry else "APPLIED"
    print("=" * 70)
    print("[{}] 写入文件数 = {}".format(tag, len(changed)))
    print("  已有头跳过 = {}".format(len(skipped)))
    print("  shebang 错位自愈 = {}".format(len(fixed)))
    for r in fixed:
        print("      ! " + r)
    print("  带 BOM（未处理，需人工） = {}".format(len(bom_list)))
    for r in bom_list:
        print("      - " + r)
    crlf = len(counts.get("b'\\r\\n'", []))
    lf = len(counts.get("b'\\n'", []))
    print("  CRLF 文件 = {}   LF 文件 = {}".format(crlf, lf))
    lf_files = counts.get("b'\\n'", [])
    if lf_files:
        print("  LF 文件清单（应为 .sh / manifest 一类）：")
        for r in sorted(lf_files):
            print("      - " + r)
    print("=" * 70)


if __name__ == "__main__":
    main()
