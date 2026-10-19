/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

import org.jetbrains.kotlin.gradle.dsl.KotlinProjectExtension
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

// ── 这里曾经放过 SonarQube（org.sonarqube 7.5.0.8588），已移除 ──────────────
//
// 结论是「不适合本项目」，理由是实跑出来的，不是看文档猜的。本机起过社区版
// 26.10 做过一次端到端分析，扫描本身没问题（281 个文件全进、145 条 Kotlin
// 规则全激活），但规则集的内容对不上这个项目的风险面：
//
//   · Kotlin 规则总数 145，Java 是 791 —— 只有 18%。
//   · Kotlin 的 31 条「安全」规则里约 12 条是 Android 专用
//     （广播权限 / KeyStore / WebView / debuggable），对桌面数据库客户端无意义。
//   · **Kotlin 规则集里一条 SQL 规则都没有。** Java 那边有 8 条，含
//     java:S2077（SQL 不得动态拼接）、java:S6912（JDBC 应用批处理）、
//     java:S9142（PreparedStatement 不应写在循环里）、java:S1192（字符串字面量
//     不得重复）。而本仓库 createStatement/prepareStatement 调用 350 处 /
//     41 个文件，SQL 字面量 771 处 / 79 个文件 —— 恰好全在覆盖盲区里。
//
// 结论：33,274 行代码只报 3 条问题，读起来像「很干净」，实际是「不看最容易
// 出错的地方」。这类假安全感比没有工具更糟，所以宁可不要。
//
// 它唯一给出、而其他工具给不出的信号是复杂度（DatabaseBrowserScreen.kt
// 圈复杂度 552，是第二名的 2.5 倍）与重复率（12.3%）——这两项在评估里记下了，
// 但不必为此常驻一个吃内存的服务 + 一个挂在根构建上的插件。
//
// SQL 注入这一块由 CodeQL 的 java/sql-injection 查询集覆盖（它对 Kotlin 生效），
// 所以真正该看的地方并没有因此失去检查。

// ktlint 本体版本先在根作用域求值一次：下面 subprojects {} 的 receiver 是
// Project，lambda 内部再引用 libs 生成的类型安全访问器容易踩解析坑。
val ktlintVersion = libs.versions.ktlint.get()

subprojects {
    // 插件只在子项目真的挂了 Kotlin 插件时才激活——不为不编译的代码做检查。
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    // ── JDK 25：全仓库统一钉住 ──────────────────────────
    //
    // 以前**没有任何声明**：编译目标完全跟着「跑 Gradle 的那个 JVM」走 ——
    // 本机是 Zulu 25.0.4.1，CI 靠各流水线自己的 `java-version: '25'` 保证。
    // 于是「用哪个版本编」是一件谁都没写下来的事：本机升级 JDK、CI 换镜像，
    // 字节码目标就静默漂移，而仓库里没有任何东西能证明它变过。
    //
    // 为什么写在根上而不是各模块自己声明：11 个模块分散写，迟早会漏掉一个；
    // 漏掉的那个会跟别的模块编出**不同版本**的字节码，而这种差异到运行时才暴露
    // （UnsupportedClassVersionError，且只在用到新 API 时才炸）。
    //
    // 本机没装 JDK 25 也不至于卡死：settings.gradle.kts 装了
    // `org.gradle.toolchains.foojay-resolver-convention`，foojay 会自动拉一个。
    //
    // ⚠️ 这一条只管**编译目标**，不管打包：Compose Desktop 的 jlink / jpackage
    // 用的是 **Gradle 运行时自己的 JVM**（`java -version` 那个），不是这里的
    // toolchain。要让安装包里的运行时也是 25，得让 Gradle 本身跑在 25 上 ——
    // 本机与五条 CI 流水线目前都恰好满足。
    plugins.withId("org.jetbrains.kotlin.jvm") {
        project.extensions.configure<KotlinProjectExtension> {
            jvmToolchain(25)
        }
    }
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        project.extensions.configure<KotlinProjectExtension> {
            jvmToolchain(25)
        }
    }

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
