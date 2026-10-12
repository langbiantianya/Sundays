package com.kxxnzstdsw.grpc

/**
 * 给出站请求盖上**界面语言**标记（BCP-47，如 `de` / `zh-Hant`）。
 *
 * ## 为什么放在 `:engine-protocol` 而不是 `:i18n`
 *
 * 这行代码要碰 proto 类型，而 `:i18n` 刻意零依赖（见它的 `build.gradle.kts` 注释）。
 * 若把签名写成 `withLanguage(language: AppLanguage)`，`:i18n` 就得依赖 `:engine-protocol`
 * —— 于是文案表模块反过来依赖协议模块，与 `:engine` / `:shared` 依赖 `:i18n` 的方向
 * 构成环。故这里只收一个 `String`，由调用方负责把枚举转成标签。
 *
 * ## 为什么必须有它：两个 `EngineClient` 实现都要用同一条
 *
 * | 实现 | 引擎进程 | 语言怎么到 |
 * |---|---|---|
 * | `IdbEngine` | 与 UI 同 JVM | 仍要走 proto —— 因为 dispatcher 在 `Dispatchers.IO` 上跑，<br>UI 线程的 ThreadLocal 到不了那里（见 `EngineLanguageContext`） |
 * | `GrpcEngineClient` | 独立进程 | 只能走 proto，`ThreadLocal` 跨不过进程边界 |
 *
 * 两条路都落在 `Request.options.language` 上，`RequestDispatcher` 那一侧就只需要认一个来源。
 *
 * @param tag BCP-47 标签。空串等价于「不标记」，引擎会按自身 locale 探测。
 * @return 盖过标记的请求；已经是该值时**原样返回同一个实例**（protobuf 消息不可变，
 *   重建没有收益，而请求对象是热路径上每秒可能过几十个的量）。
 */
fun Request.withLanguageTag(tag: String): Request {
    if (tag.isEmpty()) return this
    // `options` 在未设置时返回默认实例，取值安全；`toBuilder()` 才决定是否真的带上这个字段。
    if (hasOptions() && options.language == tag) return this
    return toBuilder()
        .setOptions(options.toBuilder().setLanguage(tag))
        .build()
}
