/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

plugins {
    kotlin("jvm")
}

group = "com.langbiantianya"
version = "1.0-SNAPSHOT"

// JDK 25 由根构建统一声明（见根 `build.gradle.kts` 的 `subprojects` 块）——
// 本模块**不单独**声明 `jvmToolchain(…)` / `sourceCompatibility`。
//
// 这里曾经刻意「不写死」：担心「本机没装那个版本 + foojay 拉不动」的机器配不出
// 任务图。那条顾虑已经不存在了 —— 根构建统一声明后，foojay resolver
// （settings.gradle.kts）在任何机器上都会先把 JDK 25 备齐，而分散声明只会漏掉某个模块、
// 让它跟别的模块编出不同版本的字节码。
//
// ⚠️ 但**`sourceCompatibility` 仍然不适用**：那会把「编译目标」与「语言特性级别」绑死，
// 而文案表是纯 Kotlin 源码，对字节码版本没有任何要求。

// ============================================================================
// 这个模块**刻意不依赖任何东西**
// ============================================================================
//
// 它同时被两个方向完全不同的消费方使用：
//
// - `:shared` —— KMP（只有 jvm() 一个 target）的 Compose UI
// - `:engine` —— 纯 kotlin-jvm 的服务端进程，可独立于 UI 以 gRPC 方式部署
//
// 故这里既不能有 Compose（`:engine` 用不上，且会把 skiko 拖进服务端镜像），
// 也不能有 kotlinx.serialization（`:engine` 虽然有，但文案表不需要序列化：
// 六份表都是编译期常量，序列化只会在每次语言切换时多做一次无谓的解析）。
//
// 纯 Kotlin + JDK 是这个模块能同时喂给两边的**唯一**交集。
//
// 语言如何传到 `:engine` 侧见 `LanguageScope` 的 KDoc —— 那条链路由
// `RequestOptions.language`（proto 字段 4）承载，是跨进程时唯一走得通的路。

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
