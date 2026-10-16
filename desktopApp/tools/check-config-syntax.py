#!/usr/bin/env python3
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.
# 
# Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>

# -*- coding: utf-8 -*-
"""校验注入 MPL 头后，各种配置文件的语法仍然合法。

加一行注释看起来无害，但每种格式的容忍度不同：
XML 的注释不能出现在某些位置、YAML/TOML 的首行有特殊语义、
proto 对注释位置也有约束。这些解析一遍才算「没改坏」。
"""
import json
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:
    tomllib = None

try:
    import yaml
except ModuleNotFoundError:
    yaml = None

REPO = Path(__file__).resolve().parents[2]
fails = []

def check(label, fn):
    try:
        fn()
        print("  [OK]   " + label)
    except Exception as e:
        print("  [FAIL] {} : {}".format(label, e))
        fails.append(label)

print("=" * 70)
print("配置文件语法校验")
print("=" * 70)

for rel in sorted((REPO / ".github/workflows").glob("*.yml")):
    if yaml is None:
        print("  [SKIP] PyYAML 未安装，无法校验 " + rel.name)
        break
    def fn(rel=rel):
        yaml.safe_load(rel.read_text(encoding="utf-8"))
    check("YAML " + rel.name, fn)

def check_toml(rel):
    if tomllib is None:
        raise RuntimeError("Python < 3.11，无 tomllib")
    with open(rel, "rb") as f:
        tomllib.load(f)
check("TOML gradle/libs.versions.toml", lambda: check_toml(REPO / "gradle/libs.versions.toml"))

for rel in [
    REPO / "engine/src/main/resources/logback.xml",
    REPO / "packaging/flatpak/com.langbiantianya.sundays.json",
]:
    if rel.suffix == ".xml":
        check("XML " + rel.name, lambda rel=rel: ET.parse(rel).getroot())
    else:
        check("JSON " + rel.name, lambda rel=rel: json.loads(rel.read_text(encoding="utf-8")))

for rel in sorted((REPO / "engine-protocol/src/main/proto").glob("*.proto")):
    def fn(rel=rel):
        txt = rel.read_text(encoding="utf-8")
        if "Mozilla Public" not in txt.split("syntax")[0]:
            raise RuntimeError("头部不在 syntax 声明之前")
        if "syntax" not in txt:
            raise RuntimeError("找不到 syntax 声明")
    check("PROTO " + rel.name, fn)

print("=" * 70)
print("失败数 = {}".format(len(fails)))
sys.exit(1 if fails else 0)
