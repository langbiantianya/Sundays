#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# 
# Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>

# -*- coding: utf-8 -*-
"""全仓 markdown 链接体检：死文件链接 + 死锚点。

文档瘦身把大量章节标题删掉了，跨文档的 `§x.y` 锚点链接最容易悄悄失效。
按标题推 GitHub 锚点（小写、去标点、空格转连字符），逐条核对。
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LINK = re.compile(r'\[([^\]]*)\]\((\.{1,2}/[^)\s]+?)(#[^)\s]*)?\)')
HEADING = re.compile(r'^#{1,6} +(.+?)\s*$', re.M)

# 旧的编号小节（§2.8 之类）在精简后必然没了，单独归类便于判断
def slug(text):
    t = text.strip().lower()
    t = re.sub(r'`', '', t)
    t = re.sub(r'[^\w\u4e00-\u9fff \-]', '', t)
    return t.replace(' ', '-')

def headings_of(path):
    try:
        raw = path.read_bytes()
        if raw.startswith(b'\xef\xbb\xbf'):
            raw = raw[3:]
        txt = raw.decode('utf-8', errors='replace')
    except OSError:
        return None
    return {slug(m.group(1)) for m in HEADING.finditer(txt)}

def main():
    files = [p for p in subprocess.run(
        ['git', '-C', str(REPO), 'ls-files', '*.md'],
        capture_output=True, text=True, check=True).stdout.split() ]

    dead_file, dead_anchor = [], []
    cache = {}

    for rel in files:
        p = REPO / rel
        try:
            txt = p.read_bytes().decode('utf-8', errors='replace')
        except OSError:
            continue
        for m in LINK.finditer(txt):
            target, anchor = m.group(2), m.group(3)
            abs_t = (p.parent / target).resolve()
            try:
                abs_t.relative_to(REPO.resolve())
            except ValueError:
                continue                       # 指向仓库外，跳过
            if not abs_t.exists():
                dead_file.append((rel, target + (anchor or '')))
                continue
            if not anchor:
                continue
            key = str(abs_t)
            if key not in cache:
                cache[key] = headings_of(abs_t)
            if cache[key] is None:
                continue
            if anchor.lstrip('#').lower() not in cache[key]:
                dead_anchor.append((rel, target + anchor))

    print('=' * 70)
    print('markdown 链接体检：共 {} 个文档'.format(len(files)))
    print('=' * 70)
    if dead_file:
        print('\n[死文件链接] {} 条'.format(len(dead_file)))
        for rel, t in dead_file:
            print('  {} -> {}'.format(rel, t))
    if dead_anchor:
        print('\n[死锚点] {} 条'.format(len(dead_anchor)))
        for rel, t in dead_anchor:
            print('  {} -> {}'.format(rel, t))
    if not dead_file and not dead_anchor:
        print('\n[OK] 全部相对链接与锚点均有效')
    total = len(dead_file) + len(dead_anchor)
    print('\n问题总数 = {}'.format(total))
    return 1 if total else 0

if __name__ == '__main__':
    sys.exit(main())
