/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            // 国际化文案表 —— 本模块**唯一**的 project 依赖。
            //
            // 为什么打破「:shared 零 project 依赖」：文案表必须同时喂给 `:engine`
            // （引擎报错会上屏，见 i18n/README），而 `:engine` 看不到 `:shared`。
            // 文案表因此下沉成独立的 `:i18n` 模块，两边都依赖它 —— 而不是把它留在
            // 这里、再让引擎反向依赖 UI 模块。
            //
            // `:i18n` 是 plain kotlin-jvm，本模块的 `jvm()` target 消费它没有问题；
            // 反过来若将来给 `:shared` 加真正的移动端 target，需要给 `:i18n` 也补上
            // 对应 target（文案表本身没有任何 JVM 专有 API，迁移成本很低）。
            implementation(project(":i18n"))

            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.material.icons.extended)

            // 协程 —— 目前只有「表结构编辑弹窗」用它（rememberCoroutineScope + launch 去
            // 按需拉引用表的列名）。现在能编过是靠 Compose runtime 的**传递依赖**，
            // 但那不是约定：Compose 一旦收紧传递依赖，这里就会断，而断的时候报错信息
            // 指向 `launch` 未解析，与真正的原因隔了三层。显式声明。
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
