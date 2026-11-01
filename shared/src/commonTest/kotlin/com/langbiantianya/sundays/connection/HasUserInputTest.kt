/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.connection

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 「用户填过东西没有」这条判据的契约。
 *
 * ## 它管的是哪条红线
 *
 * 连接向导第 3 步有一条红字「请补齐必填字段」。它只该在用户**动过字段却仍不合法**
 * 时出现 —— 一进页面就红，是把「还没开始填」说成「你填错了」。
 *
 * 这条判据最容易写错的地方是**拿结果当输入**：JDBC URL 是从字段折算出来的，
 * 「URL 为空」既可能是「还没填」，也可能是「填了但不合法」—— 用它当判据，
 * 红字就会先于用户的任何动作出现。
 */
class HasUserInputTest {
    private fun config(
        host: String = "",
        port: Int? = null,
        database: String = "",
        username: String = "",
        password: String = "",
    ) = ConnectionConfig(
        id = "c1",
        name = "c",
        dialect = DialectType.MYSQL,
        connectionType = ConnectionType.CLIENT_SERVER,
        host = host,
        port = port,
        database = database,
        username = username,
        password = password,
    )

    @Test
    fun `什么都没填时不算动过`() {
        assertFalse(hasUserInput(config()))
    }

    @Test
    fun `填了主机地址就算动过`() {
        assertTrue(hasUserInput(config(host = "192.168.1.5")))
    }

    @Test
    fun `填了端口就算动过`() {
        // 端口是 0 也算「动过」：用户确实在那个框里按了键，
        // 只是内容还不合法 —— 那正是「红字该出现」的情形。
        assertTrue(hasUserInput(config(port = 0)))
    }

    @Test
    fun `填了库名就算动过`() {
        assertTrue(hasUserInput(config(database = "demo")))
    }

    @Test
    fun `填了用户名就算动过`() {
        // 只填了用户名、主机还空着 —— 也算动过：红字该告诉他「还缺主机」，
        // 而不是一声不吭地把「下一步」禁掉。
        assertTrue(hasUserInput(config(username = "root")))
    }

    @Test
    fun `填了密码就算动过`() {
        assertTrue(hasUserInput(config(password = "secret")))
    }
}
