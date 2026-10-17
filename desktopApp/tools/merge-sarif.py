#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把多个 SARIF 文件合并成**单个 run**，供 GitHub code scanning 一次性上传。

## 为什么需要它

ktlint 的 Gradle 插件会给「每个模块 × 每个 source set」各生成一份 SARIF ——
本仓库 12 个模块下来是 34 份。而 `github/codeql-action/upload-sarif` 源码里
有一道硬性检查：

    throw new ConfigurationError(
      `The CodeQL Action does not support uploading multiple SARIF runs
       with the same category. Please update your workflow to upload a
       single run per category.`)

也就是说**同一个 category 下不能有多个 run**。一份份分开传就要开 34 个 step，
每份还得配一个不同的 category，不现实。于是这里把它们压成一个 run。

## 为什么能这么合

SARIF 的 `run` 语义是「某个工具对某个目标的一次分析」。本仓库这 34 份
全部来自同一个工具（ktlint 1.8.0），分析的是同一个目标（整个仓库），
只是被插件按 source set 切开了 —— 所以合并回一个 run 在语义上是对的。

合并安全的前提有两个，都在这里校验，**不满足就报错退出而不是硬合**：

1. 所有文件的 `%SRCROOT%` 必须一致。不一致时每个结果算出的绝对路径基准
   不同，硬合出来的文件路径全是错的。
2. 结果项必须用 `ruleId`（字符串）引用规则，而不是 `ruleIndex`（下标）。
   用下标的话合并后下标会指向别的规则，必须逐条重算。
   （ktlint 的 SARIF 用的是 ruleId，实测确认。）

用法：
    python desktopApp/tools/merge-sarif.py <输入目录> <输出文件>
"""

import glob
import json
import os
import sys


def main(argv):
    if len(argv) != 2:
        print(__doc__)
        return 2
    src_dir, out_path = argv
    files = sorted(glob.glob(os.path.join(src_dir, "*.sarif")))
    files = [f for f in files if os.path.getsize(f) > 0]
    if not files:
        print("[merge-sarif] %s 里没有非空 SARIF，无需合并。" % src_dir)
        return 0

    base = None
    results = []
    rules = {}
    srcroots = set()
    used_rule_index = False

    for path in files:
        try:
            data = json.load(open(path, encoding="utf-8"))
        except (OSError, ValueError) as exc:
            print("[merge-sarif] 读不了 %s：%s —— 已跳过" % (os.path.basename(path), exc))
            continue
        runs = data.get("runs") or []
        if not runs:
            continue
        if len(runs) > 1:
            print("[merge-sarif] %s 里有 %d 个 run，只取第一个。"
                  % (os.path.basename(path), len(runs)))
        run = runs[0]
        srcroots.add(run.get("originalUriBaseIds", {}).get("%SRCROOT%", {}).get("uri", ""))
        for res in run.get("results", []):
            if "ruleIndex" in res:
                used_rule_index = True
            results.append(res)
        for rule in run.get("tool", {}).get("driver", {}).get("rules", []):
            rules.setdefault(rule.get("id"), rule)

    if not base:
        base = {
            "tool": {"driver": {"name": "ktlint", "informationUri":
                                "https://github.com/pinterest/ktlint/"}},
        }
    if len(srcroots) > 1:
        print("[merge-sarif] 各文件的 %%SRCROOT%% 不一致，拒绝合并：")
        for s in sorted(srcroots):
            print("    %s" % (s or "<空>"))
        print("  硬合出来的路径会全是错的。要合并必须先让它们指向同一个基准目录。")
        return 1
    if used_rule_index:
        print("[merge-sarif] 结果项里出现了 ruleIndex（下标引用），拒绝合并："
              "合并后下标会指向别的规则，必须逐条重算。")
        return 1

    srcroot = srcroots.pop() if srcroots else ""
    # ktlint 自己写出来的 SARIF 里 tool.driver.rules 是**空数组**，只在结果项里
    # 放了个 ruleId 字符串。GitHub 能容忍，但输出就不是自描述的了 ——
    # 这里把结果里实际用到的 ruleId 补成规则条目，声明的合并声明的取并集。
    for res in results:
        rid = res.get("ruleId")
        if rid and rid not in rules:
            rules[rid] = {
                "id": rid,
                "name": rid,
                "shortDescription": {"text": rid},
            }

    merged = {
        "$schema": "https://raw.githubusercontent.com/oasis-tcs/sarif-spec/master/"
                   "Schemata/sarif-schema-2.1.0.json",
        "version": "2.1.0",
        "runs": [{
            "originalUriBaseIds": {"%SRCROOT%": {"uri": srcroot}},
            "tool": {"driver": {
                "name": "ktlint",
                "fullName": "ktlint",
                "organization": "pinterest",
                "informationUri": "https://github.com/pinterest/ktlint/",
                "downloadUri": "https://github.com/pinterest/ktlint/releases/tag/1.8.0",
                "semanticVersion": "1.8.0",
                "version": "1.8.0",
                "rules": list(rules.values()),
            }},
            "results": results,
        }],
    }
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, indent=2)
    print("[merge-sarif] %d 个文件 -> 1 个 run，%d 条结果，%d 条规则，%s"
          % (len(files), len(results), len(rules), out_path))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
