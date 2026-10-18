/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.sundays.connection.DialectType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [TableStructureDraft.validate] / [TableStructureDraft.diff] 的行为约定。
 *
 * ## 这套逻辑的风险不对称
 *
 * 差分算错的后果分两类，量级差得很远：
 * - **多算一条** —— 多发一句无害的 DDL，用户看得到。
 * - **少算一条** —— 比如把改名算成「删 + 加」，中途失败就真的丢掉整列数据；
 *   或者漏掉一条外键的 DROP，用户以为删掉了而库里还留着。
 *
 * 所以下面每条断言都钉的是「**不能少算**」的那一侧，而不只钉结果长什么样。
 */
class TableStructureDiffTest {
    // ------------------------------------------------------------------
    // 构造辅助
    // ------------------------------------------------------------------

    private fun col(
        name: String,
        type: String = "INTEGER",
        originalName: String = name,
        length: String = "",
        primaryKey: Boolean = false,
        nullable: Boolean = true,
        autoIncrement: Boolean = false,
        defaultValue: String = "",
    ) = ColumnDraft(
        originalName = originalName,
        name = name,
        type = type,
        length = length,
        primaryKey = primaryKey,
        nullable = nullable,
        autoIncrement = autoIncrement,
        defaultValue = defaultValue,
    )

    /** 用户在弹窗里新加的行：[ColumnDraft.originalName] 为空。 */
    private fun newCol(
        name: String,
        type: String = "INTEGER",
    ) = col(name, type = type, originalName = "")

    private fun fk(
        name: String,
        column: String,
        refTable: String,
        refColumn: String,
        original: ForeignKeyDraft.ForeignKeyDefinition? = null,
        onDelete: String = "NO ACTION",
        onUpdate: String = "NO ACTION",
    ) = ForeignKeyDraft(
        name = name,
        original = original,
        column = column,
        refTable = refTable,
        refColumn = refColumn,
        onDelete = onDelete,
        onUpdate = onUpdate,
    )

    private fun definition(
        name: String,
        column: String,
        refTable: String,
        refColumn: String,
        onDelete: String = "NO ACTION",
        onUpdate: String = "NO ACTION",
    ) = ForeignKeyDraft.ForeignKeyDefinition(name, column, refTable, refColumn, onDelete, onUpdate)

    /**
     * 一张**已存在**的表刚打开弹窗时的样子：[columns] 与 [originalColumns] 是同一份。
     * 后面所有的「编辑」都只动其中一份 —— 这正是真实弹窗里的样子。
     */
    private fun existing(
        vararg columns: ColumnDraft,
        foreignKeys: List<ForeignKeyDraft> = emptyList(),
    ) = TableStructureDraft(
        database = "db",
        schema = "PUBLIC",
        tableName = "t",
        originalName = "t",
        isNew = false,
        columns = columns.toList(),
        originalColumns = columns.toList(),
        foreignKeys = foreignKeys,
        originalForeignKeys = foreignKeys,
    )

    /** 用户改完字段列表之后的样子 —— 快照不动，只动 [TableStructureDraft.columns]。 */
    private fun afterColumns(
        base: TableStructureDraft,
        vararg columns: ColumnDraft,
    ) = base.copy(columns = columns.toList())

    /** 用户改完外键列表之后的样子 —— 快照不动。 */
    private fun afterForeignKeys(
        base: TableStructureDraft,
        foreignKeys: List<ForeignKeyDraft>,
    ) = base.copy(foreignKeys = foreignKeys)

    private fun fresh(vararg columns: ColumnDraft) =
        TableStructureDraft(
            database = "db",
            schema = "PUBLIC",
            tableName = "t",
            isNew = true,
            columns = columns.toList(),
        )

    private fun drops(changes: List<StructureChange>): List<String> = changes.filterIsInstance<StructureChange.DropColumn>().map { it.name }

    private fun mods(changes: List<StructureChange>): List<StructureChange.ModifyColumn> = changes.filterIsInstance<StructureChange.ModifyColumn>()

    private fun adds(changes: List<StructureChange>): List<ColumnDraft> = changes.filterIsInstance<StructureChange.AddColumn>().map { it.column }

    private fun fkDrops(changes: List<StructureChange>): List<String> = changes.filterIsInstance<StructureChange.DropForeignKey>().map { it.name }

    private fun kinds(changes: List<StructureChange>): List<String> = changes.map { it::class.simpleName!! }

    // ------------------------------------------------------------------
    // validate
    // ------------------------------------------------------------------

    @Test
    fun `a well formed edit passes validation`() {
        assertNull(existing(col("id", primaryKey = true, nullable = false)).validate())
    }

    @Test
    fun `a new table without a name is rejected`() {
        assertNotNull(fresh(col("id")).copy(tableName = "  ").validate())
    }

    @Test
    fun `a table without columns is rejected`() {
        assertNotNull(fresh().validate())
    }

    @Test
    fun `a column without a name is rejected`() {
        assertNotNull(fresh(col("id"), col("  ", originalName = "")).validate())
    }

    @Test
    fun `a column without a type is rejected`() {
        assertNotNull(fresh(col("id"), col("x", type = " ", originalName = "")).validate())
    }

    /**
     * 主键列可空是**数据库自己**的规则（H2 / PG / MySQL 全都拒）。
     * 不在提交前拦下来，用户就要先填完一屏再从引擎错误里读一句英文。
     */
    @Test
    fun `a nullable primary key column is rejected`() {
        assertNotNull(fresh(col("id", primaryKey = true, nullable = true)).validate())
    }

    /**
     * `" ID "` 与 `"id"` 在库里是同一列（未加引号时 PG 会折叠大小写，空白更是被
     * 当作书写噪声）。如果按原样比较，重复检查会漏掉真正的冲突，提交时才报一个
     * 用户看不懂的「列已存在」。
     */
    @Test
    fun `column names differing only by case and blanks collide`() {
        assertNotNull(fresh(col("id"), col(" ID ", originalName = "")).validate())
    }

    @Test
    fun `an incomplete foreign key is rejected`() {
        val draft =
            fresh(col("id"), newCol("owner_id")).copy(
                foreignKeys = listOf(fk("fk1", "owner_id", "", "id")),
            )
        assertNotNull(draft.validate())
    }

    /** 外键指向的列必须真的在这张表里 —— 十有八九是手滑填错了名字。 */
    @Test
    fun `a foreign key pointing at an unknown column is rejected`() {
        val draft =
            fresh(col("id")).copy(
                foreignKeys = listOf(fk("fk1", "typo", "users", "id")),
            )
        assertNotNull(draft.validate())
    }

    @Test
    fun `a complete foreign key passes`() {
        val draft =
            fresh(col("id"), newCol("owner_id")).copy(
                foreignKeys = listOf(fk("fk1", "owner_id", "users", "id")),
            )
        assertNull(draft.validate())
    }

    /**
     * 复合外键是「读回来就改不了」的那种：[TableStructureDraft.validate] 必须跳过它。
     * 不跳过的话用户打开一张有复合外键的表，保存按钮永远是灰的，且看不出原因。
     */
    @Test
    fun `a composite foreign key does not block validation`() {
        val composite = fk("fk_complex", "", "orders", "", original = definition("fk_complex", "", "orders", ""))
        assertTrue(composite.isReadOnly, "复合外键必须被认成只读")
        assertNull(existing(col("id"), foreignKeys = listOf(composite)).validate())
    }

    // ------------------------------------------------------------------
    // diff —— 新建
    // ------------------------------------------------------------------

    @Test
    fun `a new table diffs to everything added`() {
        val changes =
            fresh(col("id"), col("name", type = "VARCHAR"))
                .copy(
                    foreignKeys = listOf(fk("fk1", "name", "t", "id")),
                ).diff()
        assertEquals(2, adds(changes).size)
        assertEquals(1, changes.filterIsInstance<StructureChange.AddForeignKey>().size)
        assertTrue(drops(changes).isEmpty())
    }

    /** 空白字段名不算「加一列」—— 差分里出现一条无名列只会让引擎报一个看不懂的错。 */
    @Test
    fun `a new table ignores columns with blank names`() {
        val changes = fresh(col("id"), col("   ", originalName = "")).diff()
        assertEquals(1, adds(changes).size)
    }

    // ------------------------------------------------------------------
    // diff —— 编辑
    // ------------------------------------------------------------------

    @Test
    fun `an untouched table diffs to nothing`() {
        assertTrue(existing(col("id"), col("name", type = "VARCHAR")).diff().isEmpty())
    }

    /**
     * 改名**必须**是 `MODIFY_COLUMN` 而不是「删 + 加」。
     *
     * 这一条是整个差分里最贵的断言：按当前列名去配对的话，改名会被同时算成
     * 「删 email」和「加 mail」，而那两条语句真跑下去，中途第二句失败就意味着
     * email 的数据永久没了、mail 是空的。
     */
    @Test
    fun `renaming a column is one modify not a drop plus an add`() {
        val changes =
            afterColumns(existing(col("email", type = "VARCHAR")), col("mail", type = "VARCHAR", originalName = "email")).diff()

        assertTrue(drops(changes).isEmpty(), "改名绝不能产生删列，实际=${drops(changes)}")
        assertTrue(adds(changes).isEmpty(), "改名绝不能产生加列，实际=${adds(changes)}")
        val modify = mods(changes).single()
        assertEquals("email", modify.before.trimmedName)
        assertEquals("mail", modify.after.trimmedName)
    }

    /** 改名 + 改类型合成**一条**：引擎的 MODIFY_COLUMN 同时收 type 与 new_name。 */
    @Test
    fun `renaming and retyping at once stays one modify`() {
        val changes =
            afterColumns(
                existing(col("email", type = "VARCHAR", length = "255")),
                col("mail", type = "TEXT", length = "255", originalName = "email"),
            ).diff()
        assertEquals(1, mods(changes).size)
        assertTrue(drops(changes).isEmpty())
        assertTrue(adds(changes).isEmpty())
    }

    @Test
    fun `removing a column from the draft drops it`() {
        val changes = afterColumns(existing(col("id"), col("email", type = "VARCHAR")), col("id")).diff()
        assertEquals(listOf("email"), drops(changes))
    }

    @Test
    fun `a new column is added`() {
        val changes =
            afterColumns(existing(col("id")), col("id"), newCol("note", type = "VARCHAR")).diff()
        assertEquals(listOf("note"), adds(changes).map { it.trimmedName })
        assertTrue(drops(changes).isEmpty())
    }

    /**
     * 差分顺序是「先删、再改、后加、最后外键」，理由全在
     * [TableStructureDraft.diff] 的 KDoc 里：多出来的列无害，多删一列是丢数据；
     * 外键依赖「这张表现在有这些列」，放前面就会去引用一个还不存在的列。
     */
    @Test
    fun `changes are ordered drop then modify then add`() {
        val changes =
            afterColumns(
                existing(col("id"), col("gone", type = "VARCHAR"), col("email", type = "VARCHAR")),
                col("id"),
                col("mail", type = "VARCHAR", originalName = "email"),
                newCol("extra", type = "VARCHAR"),
            ).diff()
        assertEquals(listOf("DropColumn", "ModifyColumn", "AddColumn"), kinds(changes))
    }

    @Test
    fun `whitespace and case only edits are not diffs`() {
        val changes =
            afterColumns(
                existing(col("id"), col("email", type = "VARCHAR", length = "255")),
                col("id"),
                col(" EMAIL ", type = " varchar ", length = " 255 ", originalName = "email"),
            ).diff()
        assertTrue(changes.isEmpty(), "空白/大小写变化不该产生 DDL，实际=$changes")
    }

    /** 长度是字符串输入，存的是数字；`"0"` 与空串都表示「不指定长度」，两者等价。 */
    @Test
    fun `length zero and blank are the same`() {
        val changes =
            afterColumns(
                existing(col("name", type = "VARCHAR", length = "")),
                col("name", type = "VARCHAR", length = "0", originalName = "name"),
            ).diff()
        assertTrue(changes.isEmpty(), "实际=$changes")
    }

    /** 勾掉「允许为空」必须发 DDL —— 快照里的默认值不能盖住用户的改动。 */
    @Test
    fun `toggling nullable is a modify`() {
        val changes =
            afterColumns(
                existing(col("note", type = "VARCHAR", nullable = true)),
                col("note", type = "VARCHAR", nullable = false, originalName = "note"),
            ).diff()
        assertEquals(1, mods(changes).size)
    }

    // ------------------------------------------------------------------
    // diff —— 外键
    // ------------------------------------------------------------------

    @Test
    fun `an added foreign key is added`() {
        val changes =
            afterForeignKeys(
                existing(col("id"), col("owner_id")),
                listOf(fk("fk1", "owner_id", "users", "id")),
            ).diff()
        assertEquals(1, changes.filterIsInstance<StructureChange.AddForeignKey>().size)
        assertTrue(fkDrops(changes).isEmpty())
    }

    /**
     * 用户在弹窗里把外键那一行**整行删掉**之后，草稿的 `foreignKeys` 里就没有它了 ——
     * 只有 `originalForeignKeys` 这份快照还留着证据。漏了这一条，保存会静悄悄地
     * 把那条外键留在库里，而用户以为已经删掉了。
     */
    @Test
    fun `a foreign key removed from the draft is dropped`() {
        val original = fk("fk1", "owner_id", "users", "id", original = definition("fk1", "owner_id", "users", "id"))
        val changes = afterForeignKeys(existing(col("id"), col("owner_id"), foreignKeys = listOf(original)), emptyList()).diff()
        assertEquals(listOf("fk1"), fkDrops(changes))
    }

    /**
     * 引擎没有「改约束名」这条语句，所以改名字只能删了重建。
     * 漏了这一对的话，用户改完名点保存，界面报「已保存」，库里一个字都没动。
     */
    @Test
    fun `renaming a foreign key is a drop plus an add`() {
        val def = definition("fk_old", "owner_id", "users", "id")
        val changes =
            afterForeignKeys(
                existing(
                    col("id"),
                    col("owner_id"),
                    foreignKeys = listOf(fk("fk_old", "owner_id", "users", "id", original = def)),
                ),
                listOf(fk("fk_new", "owner_id", "users", "id", original = def)),
            ).diff()
        assertEquals(listOf("fk_old"), fkDrops(changes))
        assertEquals(1, changes.filterIsInstance<StructureChange.AddForeignKey>().size)
    }

    /** 同理：协议没有 ALTER FOREIGN KEY，改引用动作也只能删了重建。 */
    @Test
    fun `changing a foreign key action is a drop plus an add`() {
        val def = definition("fk1", "owner_id", "users", "id")
        val changes =
            afterForeignKeys(
                existing(
                    col("id"),
                    col("owner_id"),
                    foreignKeys = listOf(fk("fk1", "owner_id", "users", "id", original = def)),
                ),
                listOf(fk("fk1", "owner_id", "users", "id", original = def, onDelete = "CASCADE")),
            ).diff()
        assertEquals(1, changes.filterIsInstance<StructureChange.DropForeignKey>().size)
        assertEquals(1, changes.filterIsInstance<StructureChange.AddForeignKey>().size)
    }

    /**
     * 复合外键改不了（界面画成只读行），保存时也必须**一个字节都不动**它 ——
     * 不发 DROP 也不发 CREATE。反过来做就等于一次普通保存毁掉用户没打算动的约束。
     */
    @Test
    fun `an untouched composite foreign key produces no change at all`() {
        val composite = fk("fk_complex", "", "orders", "", original = definition("fk_complex", "", "orders", ""))
        val changes = existing(col("id"), foreignKeys = listOf(composite)).diff()
        assertTrue(changes.isEmpty(), "复合外键不该产生任何 DDL，实际=$changes")
    }

    /** 外键差分排在最后：它依赖「这张表现在有这些列」。 */
    @Test
    fun `foreign key changes come after column changes`() {
        val changes =
            afterColumns(existing(col("id")), col("id"), newCol("owner_id"))
                .copy(
                    foreignKeys = listOf(fk("fk1", "owner_id", "users", "id")),
                    originalForeignKeys = emptyList(),
                ).diff()
        assertEquals("AddForeignKey", kinds(changes).last())
        assertTrue(kinds(changes).indexOf("AddColumn") < kinds(changes).indexOf("AddForeignKey"))
    }
}

/**
 * 类型下拉框的候选构造。
 *
 * 这一段存在的意义全在**「不在清单里的类型不许被悄悄换掉」**：存量表里读出来的类型
 * 可能是清单没收的扩展类型，若下拉只列清单，框里显示的就是清单第一项，用户一点
 * 保存就把类型改了 —— 而界面上从头到尾没提示过他改过什么。
 */
class TypeChoicesTest {
    @Test
    fun `a catalog type is offered and marked as known`() {
        val choices = typeChoices(DialectType.POSTGRESQL, "integer")
        assertEquals("INTEGER", choices.first().name.uppercase())
        assertTrue(choices.first().fromCatalog)
    }

    @Test
    fun `an unlisted type is put first and flagged`() {
        val choices = typeChoices(DialectType.POSTGRESQL, "  my_custom_domain  ")
        assertEquals("my_custom_domain", choices.first().name)
        assertTrue(!choices.first().fromCatalog, "清单外的类型必须被标出来")
    }

    @Test
    fun `an empty value offers the plain catalog`() {
        val choices = typeChoices(DialectType.POSTGRESQL, "")
        assertTrue(choices.isNotEmpty())
        assertTrue(choices.all { it.fromCatalog })
    }

    /**
     * 清单里 `VARCHAR` 有好几条预设（默认 255 / 不限 / PG 又来一条）。
     * 置顶时只取第一条，并且其余同名项**一并从正文里剔掉** —— 否则下拉开头会连着
     * 出现三四条一模一样的 `VARCHAR`，用户会以为点错了。
     */
    @Test
    fun `a type with several presets does not fill the head with duplicates`() {
        val choices = typeChoices(DialectType.POSTGRESQL, "VARCHAR")
        val head = choices.takeWhile { it.name.equals("VARCHAR", ignoreCase = true) }
        assertEquals(1, head.size, "VARCHAR 在清单里有好几条预设，实际=$choices")
        assertTrue(head.single().fromCatalog)
    }

    /** 库里读回来是 `INTEGER`，清单里写的是 `integer` —— 不该多出一条。 */
    @Test
    fun `matching ignores case`() {
        val choices = typeChoices(DialectType.POSTGRESQL, "INTEGER")
        assertEquals("INTEGER", choices.first().name.uppercase())
        assertEquals(
            1,
            choices.count { it.name.equals("INTEGER", ignoreCase = true) },
            "大小写不同不该导致重复项：$choices",
        )
    }

    /**
     * SQLite 只有**类型亲和性**，没有类型系统。清单必须短 ——
     * 把 `TINYINT` / `AUTO_INCREMENT` 列进去，用户点下去建出来的列与他以为的不是一回事。
     */
    @Test
    fun `sqlite offers only affinity types`() {
        val names = typeChoices(DialectType.SQLITE, "").map { it.name.uppercase() }
        assertEquals(listOf("INTEGER", "REAL", "TEXT", "BLOB"), names, "SQLite 的清单应当只有四种亲和性")
    }

    @Test
    fun `every dialect offers at least one type`() {
        DialectType.entries.forEach { dialect ->
            assertTrue(
                typeChoices(dialect, "").isNotEmpty(),
                "[$dialect] 一个类型都没给，用户会看到空下拉框",
            )
        }
    }

    /** 新列的默认类型必须真的在清单里，否则「点一下就能提交」是假的。 */
    @Test
    fun `the default new column type comes from the catalog`() {
        DialectType.entries.forEach { dialect ->
            val first = typeChoices(dialect, "").first()
            assertTrue(
                first.fromCatalog,
                "[$dialect] 默认类型 ${first.name} 不在清单里，下拉框第一项就与它对不上",
            )
        }
    }
}
