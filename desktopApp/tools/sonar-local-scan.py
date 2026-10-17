#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""本地 SonarQube 社区版代码扫描 —— 由 git pre-commit hook 调用，也可单独跑。

分析走官方推荐的 **SonarScanner for Gradle**：实际执行的就是一条普通的
`./gradlew sonar`。本脚本只负责它不负责的三件事：

  1. **前置检查** —— 服务器在不在、状态是不是 UP、token 有没有。
     这三样不满足时，Gradle 那边只会抛一堆看不出所以然的堆栈；
     在这里提前拦下来并给一行人话，省掉的是「猜」。
  2. **注入凭据** —— 把 SONAR_HOST_URL / SONAR_TOKEN 以系统属性传给 Gradle。
     官方文档：系统属性的优先级高于构建脚本里写的值，而构建脚本是被 git
     跟踪的，所以凭据只能从命令行进，绝不能落到仓库里。
  3. **决定要不要真扫** —— 见下面「两种调用方式」。

换用 Gradle 插件之后删掉的东西（原来 CLI 方案才需要）：
  · 定位 sonar-scanner 可执行文件 —— 官方原话「Installation is automatic」，
    插件自己下载引擎；
  · 从 Gradle 缓存里翻 253 个 jar 传给 sonar.java.libraries —— 插件直接用
    sourceSets.main.compileClasspath，既准又不用维护。

两种调用方式
------------
单独运行 `python desktopApp/tools/sonar-local-scan.py`
    → 一定真跑一次完整分析。这就是你主动叫它来的原因。

hook 调用 `python desktopApp/tools/sonar-local-scan.py --hook`
    → 默认只做前置检查并提示，**不跑分析**。
      因为 `./gradlew sonar` 会先触发 12 个模块的全量编译（本机约 3 分钟），
      把这个代价塞进每次 `git commit` 是不能接受的设计。
      确实想在提交时扫，设 SONAR_SCAN=1。

设计前提：**这是一个便利工具，不是一道闸门。**
本机绝大多数时候并没有跑着 SonarQube（它是个吃内存的服务，不装 Docker 或
不在本机常驻就是没有）。如果 hook 因为「连不上服务器」而拒绝提交，
那它挡住的不是缺陷，是正常工作 —— 所以每一个「条件不满足」分支都打印
一行说明然后**放行**（退出码 0）。想让它真的拦人，得显式设 SONAR_REQUIRED=1。

环境变量
--------
SONAR_HOST_URL    服务器地址，默认 http://localhost:9000（SonarQube 的默认端口）
SONAR_TOKEN       认证 token。没有它 SonarQube 会拒绝上传（默认禁止匿名分析）
SONAR_SKIP=1      直接跳过，连探测都不做
SONAR_SCAN=1      hook 模式下也真跑一次完整分析（默认只检查不跑）
SONAR_REQUIRED=1  条件不满足时以非零码退出，把 hook 变成真正的闸门
SONAR_GRADLE_ARGS 追加给 Gradle 的原始参数，空格分隔。
                  例：SONAR_GRADLE_ARGS="-x classes -x testClasses"（跳过全量编译）
"""

import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
# 分析配置已经搬进根 build.gradle.kts 的 sonar { properties { ... } } 里。
# 官方文档明确 SonarScanner for Gradle **不读** sonar-project.properties，
# 所以仓库里不再保留那个文件 —— 留着只会让人改一个没人读的东西还以为生效了。
# 这里仍要从构建脚本里把 projectKey 抠出来，只为了最后给一条直达看板的链接。
BUILD_SCRIPT = REPO / "build.gradle.kts"
DEFAULT_HOST = "http://localhost:9000"
# /api/system/status 是 SonarQube 唯一不需要鉴权的接口，探活用它最省事：
# 服务器在但还在启动（status=STARTING）和根本没人监听是两种完全不同的故障，
# 报错信息必须能让人一眼分出是哪一种。
STATUS_PATH = "/api/system/status"
TIMEOUT = 5
# projectKey 在构建脚本里长这样：
#     property("sonar.projectKey", "langbiantianya_Sundays")
# 只认这一种写法，改了写法这里读不到也只是少一条链接，不影响扫描本身。
PROJECT_KEY_RE = re.compile(r'property\(\s*"sonar\.projectKey"\s*,\s*"([^"]+)"\s*\)')


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


def read_project_key():
    if not BUILD_SCRIPT.is_file():
        return None
    found = PROJECT_KEY_RE.search(BUILD_SCRIPT.read_text(encoding="utf-8"))
    return found.group(1) if found else None


def gradle_command():
    """仓库自带的 wrapper。Windows 用 .bat，其余平台用无扩展名那个。"""
    wrapper = REPO / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        return None
    return [str(wrapper)]


def build_gradle_command(host, token):
    """拼出实际执行的 Gradle 命令行。

    单独抽出来是为了能直接断言参数对不对 —— 这条命令一跑就是好几分钟，
    真去跑一遍来验证参数拼写，代价和收益不成比例。
    """
    cmd = gradle_command()
    if not cmd:
        return None
    cmd += [
        "sonar",
        "--console=plain",
        # 系统属性优先级高于构建脚本，所以凭据只走这里，绝不落进被 git 跟踪的文件。
        # 官方文档提到 Windows 交互式命令行要用 -D "key=value" 的写法，那是
        # shell 拆参数的问题；这里用 list 形式起子进程，每个 -Dxxx=yyy 都是
        # 独立的 argv 元素，不经过 shell 拆分，不需要那对引号。
        "-Dsonar.host.url=%s" % host,
        "-Dsonar.token=%s" % token,
    ]
    extra = os.environ.get("SONAR_GRADLE_ARGS", "").strip()
    if extra:
        cmd.extend(extra.split())
    return cmd


def main(argv):
    as_hook = "--hook" in argv
    print("=" * 68)
    print("SonarQube 本地扫描" + ("（git pre-commit）" if as_hook else ""))
    print("=" * 68)

    if env_flag("SONAR_SKIP"):
        return skip("SONAR_SKIP=1")

    if not BUILD_SCRIPT.is_file():
        return skip("找不到根 build.gradle.kts，无法定位 SonarQube 配置")

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

    # wrapper 缺失属于前置检查的一部分，所以放在「要不要真扫」这个分支之前 ——
    # 否则 hook 模式会说「检查通过」，而手动真跑时才发现根本没法启动 Gradle。
    cmd = build_gradle_command(host, token)
    if not cmd:
        return skip("找不到仓库自带的 gradlew wrapper")

    # ── hook 模式的默认分支：只检查，不扫 ──────────────────────────────
    # 理由见文件头：sonar 会先触发全量编译，塞进每次提交是不可接受的。
    # 这不是「功能缺失」，是把取舍显式摆出来，而不是让人以为已经扫过了。
    if as_hook and not env_flag("SONAR_SCAN"):
        print("  前置检查通过。想在这次提交真跑一次分析：export SONAR_SCAN=1")
        print("  或直接执行：python desktopApp/tools/sonar-local-scan.py")
        return 0

    extra = os.environ.get("SONAR_GRADLE_ARGS", "").strip()
    if extra:
        print("  追加参数：%s" % extra)
    print("  执行：%s sonar" % cmd[0])
    print("  （会先编译全部模块以产出字节码，本机约 3 分钟）")
    print("-" * 68)
    sys.stdout.flush()

    try:
        rc = subprocess.call(cmd, cwd=str(REPO))
    except OSError as exc:
        return skip("无法执行 Gradle：%s" % exc)

    if rc != 0:
        print("  [sonar] Gradle 以退出码 %d 结束（分析结果仍可能已上传）" % rc)
        if env_flag("SONAR_REQUIRED"):
            return rc
        return 0

    print("-" * 68)
    key = read_project_key()
    if key:
        print("  扫描完成，看板：%s/dashboard?id=%s" % (host.rstrip("/"), key))
    else:
        print("  扫描完成，看板：%s" % host.rstrip("/"))
    print("  （本 hook 只提示不阻断；要把新问题拦在提交外请设 SONAR_REQUIRED=1）")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
