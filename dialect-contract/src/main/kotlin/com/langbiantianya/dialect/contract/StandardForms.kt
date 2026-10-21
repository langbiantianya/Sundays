/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.dialect.contract

import com.langbiantianya.i18n.StrKey

/**
 * 常用表单形状 —— 给方言作者复用的**构造器**。
 *
 * ## 为什么要有它
 *
 * 内置的 5 个方言里有两组形状是**完全一样**的：
 *
 * - MySQL 与 PostgreSQL 都是「主机 + 端口 + 库名 + 账密 + JDBC URL」六格；
 * - H2（内存 / 文件）、DuckDB、SQLite 都是「一个目标字段 + 折算出来的 JDBC URL」。
 *
 * 让两个方言各写一遍 30 行几乎一样的字段清单，等于把「形状」又复制了一份 ——
 * 而形状分叉的后果正是这套机制要消灭的东西（改一处漏一处 → 界面与实际脱节）。
 *
 * ## 它只是构造器，不是「标准方言」
 *
 * 这里的「标准」指的是**形状**（几个字段、什么顺序、什么类型），
 * 不是「标准方言」。字段的**文案**仍然由调用方给：
 * DuckDB 与 SQLite 的「目标字段」标签一样，但占位提示不同
 * （`DATABASE_FILE_PATH_HINT_DUCKDB` vs `DATABASE_FILE_PATH_HINT_SQLITE`）。
 */
object StandardForms {
    /**
     * 客户端/服务端形状 —— 主机 / 端口 / 库名 / 账密 / JDBC URL。
     *
     * @param jdbcUrlPlaceholder JDBC URL 输入框的占位示例（字面量，不翻译）。
     */
    fun clientServer(jdbcUrlPlaceholder: String): ConnectionForm =
        ConnectionForm(
            connectionType = ConnectionFormTypes.CLIENT_SERVER,
            fields =
                listOf(
                    ConnectionField(
                        id = ConnectionFieldIds.HOST,
                        labelKey = StrKey.HOST,
                        type = FieldType.TEXT,
                        required = true,
                        placeholderKey = StrKey.HOST_HINT,
                    ),
                    ConnectionField(
                        id = ConnectionFieldIds.PORT,
                        labelKey = StrKey.PORT,
                        type = FieldType.INT,
                        required = true,
                    ),
                    ConnectionField(
                        id = ConnectionFieldIds.DATABASE,
                        labelKey = StrKey.DATABASE_NAME,
                        type = FieldType.TEXT,
                        placeholderKey = StrKey.DATABASE_NAME_HINT,
                    ),
                    ConnectionField(
                        id = ConnectionFieldIds.USERNAME,
                        labelKey = StrKey.USERNAME,
                        type = FieldType.TEXT,
                    ),
                    ConnectionField(
                        id = ConnectionFieldIds.PASSWORD,
                        labelKey = StrKey.PASSWORD,
                        type = FieldType.PASSWORD,
                        secret = true,
                    ),
                    // JDBC URL 之前是每个分支各写一遍的输入框（客户端/服务器分支才有），
                    // 现在由 spec 声明：它在「凭据」之前有一条分隔线，且是多行输入。
                    ConnectionField(
                        id = ConnectionFieldIds.JDBC_URL,
                        labelKey = StrKey.FIELD_JDBC_URL,
                        type = FieldType.TEXT,
                        placeholder = jdbcUrlPlaceholder,
                        separatorBefore = true,
                        multiline = true,
                    ),
                ),
        )

    /**
     * 嵌入式形状 —— 一个目标字段（库名 / 文件路径）+ 只读展示折算出来的 JDBC URL。
     *
     * ⚠️ JDBC URL 在这里是**只读展示**（[FieldType.DISPLAY]）而不是输入框：
     * 嵌入式方言的 URL 完全由目标字段折算出来，让用户手改它只会与目标字段打架
     * （改字段会重写 URL，改 URL 会覆盖字段）—— 两处真相源。
     *
     * @param connectionType 该形状适用的连接类型
     * @param labelKey 目标字段的标签
     * @param placeholderKey 目标字段的占位提示
     */
    fun embedded(
        connectionType: String,
        labelKey: StrKey,
        placeholderKey: StrKey,
    ): ConnectionForm =
        ConnectionForm(
            connectionType = connectionType,
            fields =
                listOf(
                    ConnectionField(
                        id = ConnectionFieldIds.DATABASE,
                        labelKey = labelKey,
                        type = FieldType.TEXT,
                        placeholderKey = placeholderKey,
                    ),
                    ConnectionField(
                        id = ConnectionFieldIds.JDBC_URL,
                        labelKey = StrKey.JDBC_URL_DERIVED,
                        type = FieldType.DISPLAY,
                    ),
                ),
        )
}
