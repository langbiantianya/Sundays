package com.kxxnzstdsw.dialect

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [PostgreSQLDialect.jdbcUrlForCatalog] —— PG 跨库浏览的实现本体。
 *
 * ## 为什么这条方法必须存在
 *
 * PG 的**数据库是连接的启动参数**：JDBC 没有 MySQL 那样的 `USE`，
 * 连接一建立就锁死在 URL 指的那个库上。于是 [DatabaseDialect.switchCatalog] 对 PG
 * 只能是空实现 —— 建完池再切已经晚了。
 *
 * 后果实测（真窗口走查 `192.168.1.5:5432`）：从连接配置（URL 写死 `postgres`）连进来后，
 * 点左侧树里的 `sundays_smoke` / `examquestions`，`information_schema` 查到的
 * **全是 `postgres` 库的内容** —— 静默读到错的库，比报错危险得多。
 *
 * 所以切换必须发生在**建池时**，也就是改写 URL 的库名段。
 *
 * ## 本类为什么不需要真库
 *
 * 它是纯字符串变换，没有任何 I/O。跟 `PoolManager` 的接线（真库那侧由
 * `PostgreSQLCrossDatabaseTest` 覆盖）分开测：这里红说明「URL 拼错了」，
 * 那里红说明「没接上」。混在一起就只能看到一个笼统的失败。
 */
class PostgreSQLJdbcUrlTest {

    private val dialect = PostgreSQLDialect()

    // ───────────────────────── 正常切库 ─────────────────────────

    @Test
    fun `换掉 URL 里的库名段`() {
        assertEquals(
            "jdbc:postgresql://192.168.1.5:5432/sundays_smoke",
            dialect.jdbcUrlForCatalog("jdbc:postgresql://192.168.1.5:5432/postgres", "sundays_smoke"),
            "库名段应当被整体替换",
        )
    }

    /**
     * 参数必须**原样保留**。
     *
     * PG 的 URL 上常挂着 `currentSchema` / `sslmode` / `ApplicationName` / `options` ——
     * 建池时丢掉它们就是**行为变了**，而且往往不报错：连上了，但 search_path 不对、
     * ssl 降级了。所以这一条钉的是「只动库名段，别的手别碰」。
     */
    @Test
    fun `URL 参数原样保留`() {
        val url = "jdbc:postgresql://h:5432/postgres?currentSchema=public&sslmode=disable&ApplicationName=sundays"

        assertEquals(
            "jdbc:postgresql://h:5432/sundays_smoke?currentSchema=public&sslmode=disable&ApplicationName=sundays",
            dialect.jdbcUrlForCatalog(url, "sundays_smoke"),
            "切库只允许动 `/` 与 `?` 之间那一段，参数必须逐字保留",
        )
    }

    @Test
    fun `带 IPv6 字面量的 host 也能切`() {
        // authority 里有 `:` 与 `[]` —— 切割必须只认 authority 之后的第一个 `/`
        assertEquals(
            "jdbc:postgresql://[::1]:5432/shop",
            dialect.jdbcUrlForCatalog("jdbc:postgresql://[::1]:5432/postgres", "shop"),
        )
    }

    @Test
    fun `URL 本来没写库名时补一段`() {
        assertEquals(
            "jdbc:postgresql://h:5432/shop",
            dialect.jdbcUrlForCatalog("jdbc:postgresql://h:5432", "shop"),
            "省略库名的 URL 应当被补上，而不是原样返回（否则等于没切）",
        )
    }

    // ───────────────────────── 不切的情况 ─────────────────────────

    @Test
    fun `catalog 为空时原样返回`() {
        val url = "jdbc:postgresql://h:5432/postgres"

        assertEquals(url, dialect.jdbcUrlForCatalog(url, ""), "没指定库就不该动 URL")
        assertEquals(url, dialect.jdbcUrlForCatalog(url, "   "), "空白库名等同于没指定")
    }

    @Test
    fun `不是 PG 的 URL 原样返回`() {
        // 反查方言失败时不该在这里炸 —— 让别处的校验去报「URL 不认识」
        val url = "jdbc:mysql://h:3306/postgres"

        assertEquals(url, dialect.jdbcUrlForCatalog(url, "shop"))
    }

    /**
     * 切到**同一个**库，URL 必须逐字不变。
     *
     * 连接配置里 `database` 与 URL 里的库名本来就是同一个（最常见的情形）。
     * 若这里产生了任何改动，要么是池被拆成两份，要么是 `currentSchema` 之类的
     * 参数被动了 —— 那种「无变化时的变化」极难查。
     */
    @Test
    fun `切到同一个库时 URL 逐字不变`() {
        val url = "jdbc:postgresql://192.168.1.5:5432/postgres?sslmode=disable"

        assertEquals(url, dialect.jdbcUrlForCatalog(url, "postgres"))
    }

    // ───────────────────────── 库名里的危险字符 ─────────────────────────

    /**
     * 库名里带 `/` `?` `#` 会**重解析 URL 结构**，拼出来的地址指向另一个库。
     *
     * 这是本方法里最要紧的一条：`catalog` 直接来自用户在树上点的那一层，不可信。
     * 静默连到错的库、读到错的表，比连不上危险得多 —— 所以一律拒绝。
     *
     * @param catalog 含结构字符的库名
     */
    @ParameterizedTest
    @ValueSource(
        strings = ["sundays/smoke", "shop?x=1", "a#b", "with space", "tab\there", "pct%20"],
    )
    fun `库名含 URL 结构字符时拒绝而不是拼接`(catalog: String) {
        val ex = assertThrows<IllegalArgumentException> {
            dialect.jdbcUrlForCatalog("jdbc:postgresql://h:5432/postgres", catalog)
        }
        assertTrue(
            ex.message!!.contains(catalog),
            "错误信息里应带上出问题的库名，界面上才看得出是哪一步失败的，实际：${ex.message}",
        )
    }
}
