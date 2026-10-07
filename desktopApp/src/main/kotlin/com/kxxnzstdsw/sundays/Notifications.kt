package com.kxxnzstdsw.sundays

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 一条通知。
 *
 * ## 为什么要有它，而不是把各处错误就地弹一下
 *
 * 应用里错误散落在**互不相干的地方**：连接失败在 `ConnectionStatus`、表预览读不到
 * 在 `TablePreviewTab.error`、SQL 报错在 `SqlSheet.error`、导出成败在后台任务里。
 * 每一处就地提示的问题很实际：用户切走之后再回来，**什么都没留下**，
 * 而「刚才那个导出到底成没成」这类问题恰恰是切走之后才想问的。
 *
 * 通知中心把这些收在一处，且**跨 sheet** —— 连接 A 失败、连接 B 的导出成功，
 * 两条通知并在同一个列表里，顺序按发生时间。
 */
data class AppNotification(
    val id: String,
    val severity: Severity,
    /** 一行标题 —— 「导出失败」「连接失败」这类**是什么**。 */
    val title: String,
    /** 详情 —— 引擎给的原文 / 文件路径。**不截断**，排障时原文比好看重要。 */
    val detail: String,
    /** 来源标识，用于「同一个错误别重复报」的去重（见 [NotificationCenter.pushOnce]）。 */
    val sourceKey: String? = null,
    val timestamp: Long,
) {
    enum class Severity {
        SUCCESS,
        ERROR,

        /** 一般信息（暂未使用，保留以便后续扩展）。 */
        INFO,
    }
}

/**
 * 通知中心 —— 屏级**共享**一个。
 *
 * ## 为什么不是每个 sheet 一个
 *
 * 通知的价值恰恰在于**跨连接**：用户在连接 A 上导出，切到连接 B 上写 SQL，
 * 这两条消息是同一个问题的两面。按 sheet 分开的话，每换一个连接就得换一遍通知列表，
 * 而用户想知道的「刚才发生了什么」就断了。
 *
 * ## 已读 / 未读
 *
 * 只记「有没有未读」，不记每条的已读状态 —— 通知条目短、列表不长，
 * 逐条已读会逼用户逐条点，而**打开面板本身**就是一次确认。
 */
class NotificationCenter {

    private val _notifications = mutableStateListOf<AppNotification>()
    val notifications: List<AppNotification> get() = _notifications

    /** 未读数 = 面板打开后新进来的条数。 */
    var unreadCount by mutableStateOf(0)
        private set

    /** 面板是否展开 —— 提到屏级，与内存详情面板同一套生命周期。 */
    var panelOpen by mutableStateOf(false)

    /**
     * 已经通知过的来源键。
     *
     * 「同一个错误只报一次」靠它：连接失败会在每次重组、每次轮询里被观察到同一个
     * `status.message`，没有它就会刷出几十条一模一样的通知。
     *
     * ⚠️ 键会在**来源消失**时移除（见 [forgetSource]），这样「失败 → 恢复 → 又失败」
     * 会再报一次 —— 用户是关心第二次的。
     */
    private val notifiedSources = mutableSetOf<String>()

    fun push(severity: AppNotification.Severity, title: String, detail: String) {
        push(AppNotification(
            id = nextId(),
            severity = severity,
            title = title,
            detail = detail,
            timestamp = System.currentTimeMillis(),
        ))
    }

    /**
     * 按来源去重地推一条。
     *
     * 同一个 [sourceKey] 在「没被 [forgetSource] 忘掉」的情况下只推一次。
     */
    fun pushOnce(sourceKey: String, severity: AppNotification.Severity, title: String, detail: String) {
        if (!notifiedSources.add(sourceKey)) return
        push(severity, title, detail)
    }

    /**
     * 来源已消失 —— 允许它下次再报。
     *
     * 由错误对账循环在检测到「某个来源的错误不再存在」时调用。
     */
    fun forgetSource(sourceKey: String) {
        notifiedSources.remove(sourceKey)
    }

    /**
     * 遗忘所有**不在** [present] 里的来源。
     *
     * 错误对账循环每帧调一次：这样「失败 → 恢复 → 又失败」会再报一次，
     * 而持续存在的同一个错误不会刷屏。
     */
    fun forgetSourcesNotIn(present: Set<String>) {
        notifiedSources.retainAll(present)
    }

    private fun push(n: AppNotification) {
        _notifications.add(0, n)
        // 上限：通知是「最近发生过的事」，不是日志。留太多会把有价值的挤出去。
        while (_notifications.size > MAX_NOTIFICATIONS) {
            _notifications.removeAt(_notifications.lastIndex)
        }
        unreadCount++
    }

    /** 打开面板即视为已读 —— 见本类的「已读 / 未读」说明。 */
    fun markAllRead() {
        unreadCount = 0
    }

    /** 清空全部通知（含未读计数）。 */
    fun clear() {
        _notifications.clear()
        unreadCount = 0
        notifiedSources.clear()
    }

    private fun nextId(): String = "n-" + java.util.concurrent.atomic.AtomicLong(NOTIFY_SEQ).incrementAndGet()

    private companion object {
        /** 列表上限。60 条足够回溯一个下午，且滚动条不会长得像日志文件。 */
        const val MAX_NOTIFICATIONS = 60
        var NOTIFY_SEQ = 0L
    }
}
