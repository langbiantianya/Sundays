/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

plugins {
    kotlin("jvm")
    alias(libs.plugins.kotlin.serialization)
}

group = "com.langbiantianya"
version = "1.0-SNAPSHOT"

// ============================================================================
// 连接表单契约 —— 「方言连得上要填什么」的唯一声明处
// ============================================================================
//
// 这个模块存在的理由，与 `:i18n` **完全同构**（见 `i18n/build.gradle.kts` 的
// 「刻意不依赖任何东西」那一节）：
//
// | 消费方 | 它要契约做什么 |
// |---|---|
// | `:api` | 方言插件在 `DatabaseDialect.connectionForm` 上**声明**自己的表单形状 |
// | `:shared` | 连接向导按 spec **渲染**表单（不再按方言写分支） |
// | `:engine` | 把 spec 填进 proto 的 `DialectInfo`，跨进程送到前端 |
// | `:desktopApp` | 把 proto 还原成 spec，喂给向导 |
//
// 依赖方向上是 `api → dialect-contract ← shared`（两边都依赖它），
// 所以它**不能**依赖 `:api`（`:api` 带 `java.sql.Connection`，
// 而且依赖方向会成环），也**不能**依赖 `:shared`（那是 UI 模块）。
//
// ## 为什么需要 kotlinx.serialization（而 `:i18n` 刻意不要）
//
// 契约里只有 `AuthMethod` 需要序列化 —— 它是 `ConnectionConfig` 的字段，
// 要跟着 `connection.json` 落盘（v3 持久化格式的一部分）。
// 其余类型（`ConnectionFormSpec` / `ConnectionField` / `FieldType`）都**不**序列化：
//
// - 它们走 proto 跨进程（proto 自带序列化），不需要 Kotlin 序列化器；
// - `ConnectionField.labelKey` 持有 `StrKey`，而 `StrKey` 在 `:i18n` 里
//   **刻意不带** kotlinx.serialization（文案表是编译期常量，
//   序列化只会在每次语言切换时多做一次无谓的解析）。
//
// 所以本模块带序列化插件，但只为了 `AuthMethod` 这一个枚举 ——
// 见 `AuthMethod.kt` 里的说明。
dependencies {
    // `StrKey` 出现在契约的**公共 API** 上（`ConnectionField.labelKey`），
    // 因此必须是 api 依赖：消费方（`:shared` / `:desktopApp`）要能读它、拿它去查文案表。
    api(project(":i18n"))

    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
