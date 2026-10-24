/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import com.langbiantianya.grpc.AnalysisAccessMode
import com.langbiantianya.grpc.ConnectionConfig
import com.langbiantianya.sundays.analysis.PullProgress
import com.langbiantianya.sundays.analysis.PullResult
import com.langbiantianya.sundays.analysis.stagingTableName
import java.nio.file.Path

/*
 * 拉取链路的**契约**。
 *
 * 真正会碰引擎的实现见 `EngineAnalysisPuller`；
 * 这里只说清「拉取」这件事的形状。
 */

/**
 * 拉取链路 —— 把远端的一张表搬进**本地 DuckDB 工作区**。
 *
 * ## 一条路由，零 CSV 中转
 *
 * ```
 * ANALYSIS.PULL_TABLE   远端 JDBC 源 →（DuckDB 联邦直查）→ 工作区表
 * ```
 *
 * DuckDB 自己就能当**联邦查询层**：它能直接把远端的行读进来。
 * 于是引擎侧就是一句 `CREATE TABLE <落点> AS SELECT * FROM <联邦扫描>` ——
 * **没有导出、没有 CSV、没有临时文件**。
 *
 * ## 为什么远端配置要**单独**放在请求体里
 *
 * 一次拉取要碰**两个**数据库 —— 远端（读）与工作区（写）——
 * 而 `Request.connection` 只有一个位置，给了工作区（落点在那儿）。
 * 另一个只能走请求体。
 *
 * 这同时解决了「凭据不进 SQL 文本」：远端配置（含密码）只在请求体里，
 * 引擎把它变成一条**只发 SELECT** 的 JDBC 连接，
 * SQL 文本里既没有凭据，也没有调用方能塞进来的语句。
 *
 * ## 为什么它是个接口
 *
 * 会话（`AnalysisSession`）要靠它把「拉完了 / 拉挂了 / 拉取消了」映射成终态。
 * 那条映射是**状态机**的一部分，值得被确定性地测。
 * 拆成接口，才能给会话塞一个**立刻返回**的假实现，把三条终态路径各测一遍。
 */
interface AnalysisPuller {
    /**
     * 拉一张远端表进工作区。
     *
     * @param requestId 本次请求的 id —— **由调用方生成**，
     *   这样取消时「叫停引擎那边正在跑的拉取」一定拿得到它。
     * @param source 远端源的会话配置 —— **必须**带 `read_only = true`
     * @param workspace 本地工作区的会话配置 —— **不带** `read_only`（工作区要能写）
     * @param workspacePath 工作区数据库**文件**的路径 —— 用来在开写之前把目录建出来
     * @param namespace 命名空间（MySQL 上是库，PG 上是 schema）
     * @param table 表名
     * @param targetSchema 落点所在的 **schema** —— 空串 = 落进 `main`
     *   （「拉取远端表」那条老行为）。「添加数据源」给的是**这个源自己的 schema**，
     *   那样两个源里有同名表也不会撞。
     * @param targetTable 落点的**表名** —— 「拉取远端表」落成 `staging_<源库>_<表>`，
     *   「添加数据源」落成**原名**（那个源里的表就叫那个名字）。
     * @param accessMode 走哪条通路（见 `AnalysisAccessMode`）：
     *   默认是纯 Java 表函数（类型映射跨机器一致）；
     *   「添加数据源」要的是 **DuckDB 官方扩展优先**，走不通再退回 JDBC。
     * @param onProgress 进度回调（在引擎的协程里调，不是主线程）
     *
     * ⚠️ **`onProgress` 刻意放在最后** —— 尾随 lambda 绑的是**最后一个**参数，
     * 调用点写 `pull(…) { progress -> … }` 才是「那个进度回调」。
     * 把新参数加在它**后面**的话，所有这样的调用点都会静默变成
     * 「把 lambda 传给 accessMode」—— 编译期才发现，代价是全仓改一遍。
     */
    suspend fun pull(
        requestId: String,
        source: ConnectionConfig,
        workspace: ConnectionConfig,
        workspacePath: Path,
        namespace: String,
        table: String,
        targetSchema: String = "",
        targetTable: String = stagingTableName(namespace, table),
        accessMode: AnalysisAccessMode = AnalysisAccessMode.ANALYSIS_ACCESS_MODE_UNSPECIFIED,
        onProgress: suspend (PullProgress) -> Unit,
    ): PullResult
}
