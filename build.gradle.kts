/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.ktlint) apply false
}

// ktlint 本体版本先在根作用域求值一次：下面 subprojects {} 的 receiver 是
// Project，lambda 内部再引用 libs 生成的类型安全访问器容易踩解析坑。
val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    // 插件只在子项目真的挂了 Kotlin 插件时才激活——不为不编译的代码做检查。
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    configure<KtlintExtension> {
        version.set(ktlintVersion)
        outputToConsole.set(true)
        ignoreFailures.set(false)

        filter {
            // 必须排除 build/：engine-protocol 的 protobuf 插件把
            // build/generated/source/proto/main/kotlin 注册成了真正的 Kotlin srcDir
            // （task.builtins { id("kotlin") } 的副作用），于是 protoc 吐出来的
            // 23000+ 行代码会混进检查和格式化范围。那是机器产物：
            //   · 格式由 protoc-gen-kotlin 决定，人工改动没有意义；
            //   · 每次重新生成都会把改动冲掉，让工作区永远处于脏状态；
            //   · 它的命名（FooKt 文件 / 外层类 IdbEngineOuterClassGrpcKt）
            //     与本项目的人工约定天然冲突，会制造永远修不完的违规。
            //
            // ⚠️ 这里**必须**用带 lambda 的 exclude(Spec) 重载，不能写成
            // exclude("**/build/**")。字符串模式是拿「相对于 FileTree 根」的路径
            // 去匹配的，而生成目录本身恰好**就是**这个 source set 的 srcDir 根，
            // 于是相对路径里根本不出现 "build/" 这一段，模式永远命中不了。
            // 踩过这个坑：加上字符串模式后重跑，违规数一条没少（34479 → 34479）。
            exclude { element ->
                element.file.invariantSeparatorsPath.contains("/build/")
            }
        }

        reporters {
            reporter(ReporterType.PLAIN)
            // SARIF 是 CI 侧接 GitHub code scanning 的唯一入口：
            // 只有它能让 ktlint 的问题以行内标注的形式出现在 PR 的 Files changed 里。
            reporter(ReporterType.SARIF)
        }
    }
}
