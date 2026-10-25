/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis

import com.langbiantianya.sundays.tree.ObjectTreeKind
import com.langbiantianya.sundays.tree.ObjectTreeNode

/*
 * 分析模式树的**右键菜单规则** —— 纯逻辑，不碰界面。
 *
 * ## 为什么这一层要单独抽出来
 *
 * 「右键哪一个节点会给删除项、删的是哪个数据源」是一条**判据**，
 * 而判据必须**只写一遍**：
 * - 界面按它决定「要不要弹菜单」；
 * - 测试按它断言「这一下不该有删除项」。
 *
 * 两边各写一套，迟早会对不上 —— 那种对不上的后果很具体：
 * 界面弹出了菜单，用户点了「删除数据源」，而它删掉的是**另一个**源。
 */

/**
 * 右键这个节点时，「删除数据源」删的是**哪一个**数据源。
 *
 * @param node 被右键的节点
 * @param deletableSourceNames 登记在册的数据源名（= 它们在工作区里占的 schema 名）。
 *   只有列在这里的才给删除项 —— 工作区自己的 `main`、用户手工建的 schema 都不在其中。
 * @return 要删的 schema 名；`null` = 这一下**不该**给删除项（于是菜单根本不弹）
 */
fun analysisDeleteSourceTarget(
    node: ObjectTreeNode,
    deletableSourceNames: Set<String>,
): String? {
    val schema =
        when (node.kind) {
            // 容器那一行显示的就是 schema 名。工作区根那一行的 `label` 是 `main`，
            // 而 `main` 永远不在清单里（它不是数据源，是分析模式自带的计算引擎）——
            // 所以工作区根**拿不到**删除项，不需要额外的特判。
            ObjectTreeKind.DATABASE -> node.label

            // 表与字段的 `namespace` 是「它所在的那个 schema」——
            // 数据源在工作区里占一个 schema，所以它就是数据源的名字。
            ObjectTreeKind.TABLE, ObjectTreeKind.COLUMN -> node.namespace
        } ?: return null
    return schema.takeIf { it in deletableSourceNames }
}
