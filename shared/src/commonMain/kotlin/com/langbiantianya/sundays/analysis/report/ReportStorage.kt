/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays.analysis.report

import com.langbiantianya.sundays.config.appConfigFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/*
 * **报表的落盘** —— `~/.config/sundays/reports.json`。
 *
 * ## 为什么是**独立**的一个文件
 *
 * 报表是**应用自己的**东西，不是用户的数据：
 *
 * - 放进工作区 DuckDB 的话，用户在自己的工作区里就能查到一张**他没建过的表**，
 *   而「这张表是什么、能不能信」在那个语境里说不清；用户清理工作区时报表也跟着没了；
 * - 放进 `connection.json` 的话，连接清单（含明文口令）与报表挤在一个文件里，
 *   一个坏了连累另一个。
 *
 * 所以它自己一个文件，与 `connection.json` / `settings.json` **同构**：
 * 同一个目录、同一种结构、同一种写法。
 *
 * ## 只存「报表的形状」，不存「查出来的数」
 *
 * 落盘的只有：报表 id / 名字 / 部件清单（类型 / 标题 / SQL）。
 *
 * **部件数据不进文件** —— 那是**派生数据**：它由 SQL 在工作区上跑出来，
 * 一落盘就开始过时。而过时的数字比没有数字更危险：用户会拿它当真的。
 * 重开应用后点一次刷新，数据就回来了。
 *
 * ## 读不出来时**改名保留**，绝不静默清空
 *
 * 文件损坏 / 格式是将来的版本时，把原文件**原样改名**成
 * `reports.json.corrupt-<时间戳>`，应用按空集继续，并把这件事明确告诉用户。
 *
 * 为什么不用「静默当成空集」（`ConnectionStorage` 现在就是这个做法）：
 * 那会把「文件坏了」变成「数据没了」—— 而后者**不可逆**。
 * 被改名的文件一个字节都没动，用户可以自己打开看、自己改回来。
 *
 * ## 写盘是「先写临时文件再原子替换」
 *
 * 直接写目标文件的话，写到一半崩了，用户的报表就只剩**半个文件**。
 */

/** 报表文件名 —— `~/.config/sundays/reports.json`。 */
const val REPORT_FILE_NAME: String = "reports.json"

/** 当前磁盘格式版本。 */
private const val PERSISTED_VERSION = 1

/**
 * 读报表的**结果** —— 三种，不是两种。
 *
 * ## 为什么「读不出来」还要再分一次
 *
 * 读不出来之后有两条路：把原文件**改名保留**（应用按空集继续），
 * 或者**连改名都失败**（文件被别的程序占着 / 盘是只读的）。
 *
 * 后者不能按「空集继续」处理 —— 那等于**接着往那个读不出来的文件上写**，
 * 而下一次它要是读出来了，写进去的内容就是**半份**（新的报表 + 旧的坏文件）。
 * 所以那种情况下应用的落盘要**停用**。
 */
sealed interface ReportLoadResult {
    /**
     * 读到了。
     *
     * @param reports 磁盘上的报表（文件不存在时是空列表 —— 那不是错误）
     */
    data class Loaded(
        val reports: List<AnalysisReport>,
    ) : ReportLoadResult

    /**
     * 读不出来，但原文件**已改名保留** —— 应用按空集继续。
     *
     * @param reports 空列表（原文件没读出来，里面有什么是不知道的）
     * @param backupPath 原文件被改名成了什么
     * @param reason 读不出来的原因
     */
    data class BackedUp(
        val reports: List<AnalysisReport>,
        val backupPath: Path,
        val reason: String,
    ) : ReportLoadResult

    /**
     * 读不出来，**而且改名也失败** —— 原文件原封不动，应用**必须停掉落盘**。
     *
     * @param reason 读不出来 / 改名失败的原因
     */
    data class Unreadable(
        val reason: String,
    ) : ReportLoadResult
}

/**
 * 报表的持久化存储。
 *
 * ## 为什么它是**一个类**而不是一个 `object`
 *
 * `ConnectionStorage` 是 `object` —— 它的路径写死在类里，测试靠改
 * `user.home` 来隔离。那种做法有一个前提：**全局只有一个实例**。
 *
 * 而报表落盘要在**两处**被用到：会话启动时读、每次改动后写。
 * 做成类，测试就能直接指向临时目录，不必去动 `user.home` 这个**全局**状态 ——
 * 而全局状态在并行跑的测试里是会互相踩的（`ConnectionStoragePermissionsTest`
 * 的注释里记着这个坑）。
 *
 * @param file 报表文件路径 —— 默认 `~/.config/sundays/reports.json`
 */
class ReportStorage(
    private val file: Path = appConfigFile(REPORT_FILE_NAME),
) {
    private val json =
        Json {
            prettyPrint = true
            // 将来给报表加了字段，老文件没那个字段时不该读不出来。
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** 这个存储管的是哪个文件 —— 界面上要把路径告诉用户（「你的报表文件在这儿」）。 */
    val path: Path get() = file

    /**
     * 读报表。
     *
     * @return 三种结果之一：读到了 / 改名保留了 / 读不出来且不能改名
     */
    fun load(): ReportLoadResult {
        if (!Files.exists(file)) return ReportLoadResult.Loaded(emptyList())
        return try {
            val content = Files.readString(file)
            val persisted = json.decodeFromString<PersistedReports>(content)
            if (persisted.version > PERSISTED_VERSION) {
                // 格式是**将来的**版本 —— 我们读出来的多半是**错的**，
                // 而「读错」比「读不到」更危险（用户会拿错数据当真的）。
                // 所以不读，原样改名保留。
                backUp("文件格式是 v${persisted.version}，本版本只认 v$PERSISTED_VERSION")
            } else {
                ReportLoadResult.Loaded(persisted.reports)
            }
        } catch (e: Exception) {
            backUp(e.message ?: "文件内容读不出来")
        }
    }

    /**
     * 写报表。
     *
     * @param reports 要落盘的报表清单
     * @return 写成功了吗
     */
    fun save(reports: List<AnalysisReport>): Boolean =
        try {
            file.parent?.let { dir -> Files.createDirectories(dir) }
            val content = json.encodeToString(PersistedReports(reports = reports, version = PERSISTED_VERSION))
            val tmp = file.resolveSibling("${file.fileName}.tmp")
            Files.writeString(tmp, content)
            // 移动失败（非原子移动不支持时）退化为普通替换，仍然不会留下半截文件
            runCatching { Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE) }
                .recoverCatching {
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
                }.getOrThrow()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }

    /**
     * 把读不出来的原文件**改名保留**。
     *
     * ## 为什么不是「删掉」也不是「覆盖」
     *
     * 删掉 = 毁掉用户的数据；覆盖 = 毁掉用户的数据**而且不留痕迹**。
     * 改名 = 一个字节都不动，但用户能自己打开看、自己改回来。
     *
     * @param reason 读不出来的原因
     * @return [ReportLoadResult.BackedUp] 或 [ReportLoadResult.Unreadable]
     */
    private fun backUp(reason: String): ReportLoadResult {
        val stamp = LocalDateTime.now().format(BACKUP_STAMP)
        val backup = file.resolveSibling("${file.fileName}.corrupt-$stamp")
        return try {
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING)
            ReportLoadResult.BackedUp(
                reports = emptyList(),
                backupPath = backup,
                reason = reason,
            )
        } catch (e: Exception) {
            // 连改名都失败（文件被占着 / 只读盘）—— 原文件**原封不动**，
            // 所以这一次的落盘必须停用，绝不能往它上面写。
            ReportLoadResult.Unreadable(
                reason = "$reason；原文件也无法改名保留：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    private companion object {
        /** 备份文件名里的时间戳格式 —— 排序后就是时间顺序。 */
        private val BACKUP_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}

/** 磁盘上的报表清单 —— 带一个版本号，将来格式变了靠它分派。 */
@Serializable
private data class PersistedReports(
    val reports: List<AnalysisReport> = emptyList(),
    val version: Int = PERSISTED_VERSION,
)
