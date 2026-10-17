#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""安装 git pre-commit hook：提交前跑一次本地 SonarQube 扫描。

为什么是「安装脚本」而不是直接把 hook 文件提交进仓库：
`.git/hooks/` **不在 git 的版本控制范围内**，所以无论怎么写进仓库，
别人 clone 下来也拿不到 —— 只能由每个人（或 CI 的 bootstrap 步骤）执行一次安装。
这和 ktlint 插件的 `addKtlintCheckGitPreCommitHook` 任务解决的是同一个问题。

幂等：重复执行只会覆盖自己生成的那一个 hook；**绝不碰已经存在的、
非本工具生成的 hook**，除非显式 --force —— 否则会静默删掉别人的东西。

用法：
    python desktopApp/tools/install-git-hooks.py            # 安装
    python desktopApp/tools/install-git-hooks.py --uninstall # 卸载
    python desktopApp/tools/install-git-hooks.py --status   # 只看当前状态
"""

import os
import stat
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
HOOK = REPO / ".git" / "hooks" / "pre-commit"
MARKER = "generated-by: desktopApp/tools/install-git-hooks.py"

HOOK_BODY = """#!/bin/sh
# {marker}
#
# 由 desktopApp/tools/install-git-hooks.py 生成，请不要手改 ——
# 改动会在下次安装时被覆盖。要改逻辑，改那个 .py 再重新安装。
#
# 它做的事：在提交前调一次本地 SonarQube 社区版扫描。
# 那个脚本**从不因为扫描失败而拒绝提交**，除非你显式设了 SONAR_REQUIRED=1
# —— 因为绝大多数时候你本机根本没跑 SonarQube，那是正常状态，不是缺陷。
#
# 临时跳过这次提交：  git commit --no-verify
# 永久跳过：          export SONAR_SKIP=1

set -e
root=$(git rev-parse --show-toplevel)
script="$root/desktopApp/tools/sonar-local-scan.py"

if [ ! -f "$script" ]; then
    echo "  [sonar] 找不到 $script，跳过" >&2
    exit 0
fi

# python 在不同机器上可能叫 python3，也可能只在 py 启动器下存在；
# 逐个试，全都找不到才放行 —— 同样不让它挡提交。
if command -v python >/dev/null 2>&1; then
    exec python "$script" --hook
elif command -v python3 >/dev/null 2>&1; then
    exec python3 "$script" --hook
elif command -v py >/dev/null 2>&1; then
    exec py -3 "$script" --hook
else
    echo "  [sonar] 找不到 python，跳过" >&2
    exit 0
fi
"""


def hook_state():
    if not HOOK.exists():
        return "absent", ""
    text = HOOK.read_text(encoding="utf-8", errors="replace")
    return ("ours" if MARKER in text else "foreign"), text


def main(argv):
    if "--status" in argv:
        state, _ = hook_state()
        print("pre-commit hook：%s  (%s)" % (state, HOOK))
        return 0

    if "--uninstall" in argv:
        state, _ = hook_state()
        if state == "absent":
            print("没有可卸载的 hook。")
            return 0
        if state == "foreign" and "--force" not in argv:
            print("pre-commit hook 不是本工具生成的，拒绝删除。")
            print("确认要删就加 --force。")
            return 1
        HOOK.unlink()
        print("已卸载：%s" % HOOK)
        return 0

    state, _ = hook_state()
    if state == "foreign" and "--force" not in argv:
        print("已存在一个**不是**本工具生成的 pre-commit hook：%s" % HOOK)
        print("覆盖它需要 --force。")
        print("（那个 hook 里可能有你自己的东西，所以默认不碰它。）")
        return 1

    HOOK.parent.mkdir(parents=True, exist_ok=True)
    # 行尾必须是 LF：git 在 Windows 上也用 sh 执行 hook，CRLF 会让
    # `command -v python` 之类拿到带 \r 的命令名而找不到命令。
    HOOK.write_text(HOOK_BODY.format(marker=MARKER), encoding="utf-8", newline="\n")
    # Windows 上没有执行位这回事，但 git for Windows 会照样用 sh 跑它；
    # 这里仍然设置一下，在 Linux/macOS 上是必需的。
    try:
        HOOK.chmod(HOOK.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
    except OSError:
        pass
    print("已安装：%s" % HOOK)
    print("接下来：docker run --rm -p 9000:9000 sonarqube:community")
    print("        起好之后在网页上生成令牌，再 export SONAR_TOKEN=...")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
