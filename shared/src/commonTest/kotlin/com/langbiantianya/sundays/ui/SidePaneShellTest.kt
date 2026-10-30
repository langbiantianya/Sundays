/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 侧边面板的**左右镜像**规则 —— [sidePaneToggleIcon] 的纯函数契约。
 *
 * ## 为什么这些断言看着琐碎
 *
 * 箭头画反了，程序**不会崩、不会报错** —— 界面上只是「点下去却往反方向收」。
 * 那种别扭没有崩溃栈、没有报错信息，**很可能永远没人报 bug**。
 *
 * 而箭头的朝向在 UI 测试里**量不出来**（图标没有可断言的身份）——
 * 所以「哪一侧 + 哪个状态 → 哪个箭头」必须是一个**能单测的纯函数**，
 * 这条镜像规则才有回归保护。
 */
class SidePaneShellTest {
    @Test
    fun the_arrow_points_at_the_edge_when_collapsing() {
        // 贴左边：面板往左收 → 左箭头。
        assertEquals(
            Icons.Filled.ChevronLeft,
            sidePaneToggleIcon(collapsed = false, edge = PaneEdge.LEFT),
        )
        // 贴右边：面板往右收 → 右箭头（**镜像**）。
        assertEquals(
            Icons.Filled.ChevronRight,
            sidePaneToggleIcon(collapsed = false, edge = PaneEdge.RIGHT),
        )
    }

    @Test
    fun the_arrow_points_away_from_the_edge_when_expanding() {
        // 贴左边：面板往右开 → 右箭头。
        assertEquals(
            Icons.Filled.ChevronRight,
            sidePaneToggleIcon(collapsed = true, edge = PaneEdge.LEFT),
        )
        // 贴右边：面板往左开 → 左箭头（**镜像**）。
        assertEquals(
            Icons.Filled.ChevronLeft,
            sidePaneToggleIcon(collapsed = true, edge = PaneEdge.RIGHT),
        )
    }

    @Test
    fun the_two_edges_are_mirrors_of_each_other() {
        // 同一个状态下，两侧的箭头必须是**互为镜像**的（一个朝左、一个朝右）。
        // 两边画成同一个箭头的话，收起与展开就分不清了。
        assertNotEquals(
            sidePaneToggleIcon(collapsed = false, edge = PaneEdge.LEFT),
            sidePaneToggleIcon(collapsed = false, edge = PaneEdge.RIGHT),
        )
        assertNotEquals(
            sidePaneToggleIcon(collapsed = true, edge = PaneEdge.LEFT),
            sidePaneToggleIcon(collapsed = true, edge = PaneEdge.RIGHT),
        )
    }
}
