#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地 SonarQube 社区版代码扫描 —— 由 git pre-commit hook 调用，也可单独跑。

设计前提：**这是一个便利工具，不是一道闸门。**
本机绝大多数时候并没有跑着 SonarQube（它是个吃内存的服务，
不装 Docker 或不在本机常驻就是没有）。如果 hook 因为「连不上服务器」
而拒绝提交，那它挡住的不是缺陷，是正常工作 —— 所以这里的每一个
「条件不满足」分支都打印一行说明然后**放行**（退出码 0）。
想让它真的拦人，得显式设 SONAR_REQUIRED=1。

环境变量
--------
SONAR_HOST_URL   服务器地址，默认 http://localhost:9000（SonarQube 的默认端口）
SONAR_TOKEN      认证 token。没有它 SonarQube 会拒绝上传（默认禁止匿名分析）
SONAR_SCANNER    sonar-scanner 可执行文件的路径。不设则依次尝试
                 PATH 上的 sonar-scanner、PATH 上的 sonar-scanner.bat
SONAR_SKIP=1     直接跳过，连探测都不做
SONAR_REQUIRED=1 条件不满足时以非零码退出，把 hook 变成真正的闸门
SONAR_NO_LIBS=1  不传 sonar.java.libraries（扫描更快，但类型解析更差）

单独运行：python desktopApp/tools/sonar-local-scan.py
由 hook 调用： python desktopApp/tools/sonar-local-scan.py --hook
"""

import json
import os
import platform
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
PROPERTIES = REPO / "sonar-project.properties"
DEFAULT_HOST = "http://localhost:9000"
# /api/system/status 是 SonarQube 唯一不需要鉴权的接口，探活用它最省事：
# 服务器在但还在启动（status=STARTING）和根本没人监听是两种完全不同的故障，
# 报错信息必须能让人一眼分出是哪一种。
STATUS_PATH = "/api/system/status"
TIMEOUT = 5


def env_flag(name):
    return os.environ.get(name, "").strip().lower() in ("1", "true", "yes", "on")


def skip(reason, hint=None):
    """条件不满足：说明原因，然后放行。"""
    print("  [sonar] 跳过：%s" % reason)
    if hint:
        print("          %s" % hint)
    if env_flag("SONAR_REQUIRED"):
        print("          SONAR_REQUIRED=1 已设置 —— 按要求判为失败。")
        return 1
    return 0


def find_scanner():
    """定位 sonar-scanner。找不到就返回 None，由调用方给出安装指引。"""
    explicit = os.environ.get("SONAR_SCANNER")
    if explicit:
        return explicit
    names = ["sonar-scanner.bat", "sonar-scanner"] if os.name == "nt" else ["sonar-scanner"]
    for name in names:
        from shutil import which
        p = which(name)
        if p:
            return p
    # 常见的自解压安装位置：解压后是一份含 bin/ 的目录。
    for base in (Path.home() / "sonar-scanner", Path("C:/") / "sonar-scanner"):
        for name in names:
            cand = base / "bin" / name
            if cand.exists():
                return str(cand)
    return None


def probe_server(host):
    """返回 (status, detail)。status 为 None 表示连不上。"""
    url = host.rstrip("/") + STATUS_PATH
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            return body.get("status", "?"), body.get("version", "?")
    except urllib.error.HTTPError as exc:
        return "HTTP %d" % exc.code, "-"
    except urllib.error.URLError as exc:
        return None, str(getattr(exc, "reason", exc))
    except (TimeoutError, OSError) as exc:
        return None, str(exc)


def gradle_libraries():
    """从 Gradle 模块缓存里收集第三方 jar，剔掉 sources/javadoc。

    官方文档：不用 SonarScanner for Gradle/Maven 时「强烈建议」提供依赖 jar，
    否则类型解析不准、会冒出一堆 unresolved symbol 的假阳性。

    本仓库实测：缓存里 507 个 jar，其中 254 个是 -sources.jar —— 一半以上。
    把源码 jar 喂给分析器毫无意义（它要的是 class），只会让扫描慢一倍。
    """
    gradle_home = os.environ.get("GRADLE_USER_HOME")
    base = Path(gradle_home) if gradle_home else Path.home() / ".gradle"
    cache = base / "caches" / "modules-2" / "files-2.1"
    if not cache.is_dir():
        return []
    jars = []
    for p in cache.rglob("*.jar"):
        name = p.name
        if name.endswith("-sources.jar") or name.endswith("-javadoc.jar"):
            continue
        jars.append(str(p))
    jars.sort()
    return jars


def dashboard_url(host, project_key):
    return "%s/dashboard?id=%s" % (host.rstrip("/"), project_key)


def read_project_key():
    if not PROPERTIES.is_file():
        return "langbiantianya_Sundays"
    for line in PROPERTIES.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line.startswith("sonar.projectKey="):
            return line.split("=", 1)[1].strip()
    return "langbiantianya_Sundays"


def main(argv):
    as_hook = "--hook" in argv
    start = "SonarQube 本地扫描" + ("（git pre-commit）" if as_hook else "")
    print("=" * 68)
    print(start)
    print("=" * 68)

    if env_flag("SONAR_SKIP"):
        return skip("SONAR_SKIP=1")

    if not PROPERTIES.is_file():
        return skip("找不到 sonar-project.properties", "仓库根目录应当有它")

    host = os.environ.get("SONAR_HOST_URL", DEFAULT_HOST).strip()
    token = os.environ.get("SONAR_TOKEN", "").strip()

    status, detail = probe_server(host)
    if status is None:
        return skip(
            "连不上 %s（%s）" % (host, detail),
            "起一个社区版：docker run --rm -p 9000:9000 sonarqube:community",
        )
    if status != "UP":
        return skip(
            "%s 的状态是 %s，不是 UP" % (host, status),
            "服务器还没起来完（首次启动要一两分钟），过一会儿再试",
        )
    print("  服务器：%s  状态 UP（版本 %s）" % (host, detail))

    if not token:
        return skip(
            "没有 SONAR_TOKEN",
            "登录 %s → 我的账号 → 安全 → 生成令牌，然后 export SONAR_TOKEN=..." % host,
        )

    scanner = find_scanner()
    if not scanner:
        return skip(
            "找不到 sonar-scanner 可执行文件",
            "从 https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/ 下载解压，"
            "然后 export SONAR_SCANNER=<解压目录>/bin/sonar-scanner.bat",
        )
    print("  扫描器：%s" % scanner)

    project_key = read_project_key()
    cmd = [scanner, "-Dsonar.projectBaseDir=%s" % REPO]
    # 这些值只从环境变量进命令行，绝不落进仓库里的 properties 文件 ——
    # 提交进 git 的 token 等于泄漏。
    cmd.append("-Dsonar.host.url=%s" % host)
    cmd.append("-Dsonar.token=%s" % token)

    libs = [] if env_flag("SONAR_NO_LIBS") else gradle_libraries()
    if libs:
        cmd.append("-Dsonar.java.libraries=%s" % ",".join(libs))
        print("  依赖 jar：%d 个（已剔除 sources/javadoc）" % len(libs))
    else:
        print("  依赖 jar：0 个 —— 类型解析会偏弱，只报得出语法级问题")

    cmd.append("-Dsonar.scanner.metadataFilePath=%s" % (REPO / "build" / "sonar-scanner-report-task.txt"))
    print("-" * 68)
    sys.stdout.flush()

    try:
        rc = subprocess.call(cmd, cwd=str(REPO))
    except OSError as exc:
        return skip("无法执行扫描器：%s" % exc)

    if rc != 0:
        print("  [sonar] 扫描器以退出码 %d 结束（分析结果仍可能已上传）" % rc)
        if env_flag("SONAR_REQUIRED"):
            return rc
        return 0

    print("-" * 68)
    print("  扫描完成，看板：%s" % dashboard_url(host, project_key))
    print("  （本 hook 只提示不阻断；要把新问题拦在提交外请设 SONAR_REQUIRED=1）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
