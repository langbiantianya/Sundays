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

    // SonarScanner for Gradle。**只挂在根项目上**，这是官方文档对多项目构建的
    // 原话要求：「apply the SonarQube Server plugin to the root project of the
    // hierarchy」—— 一次分析覆盖整棵模块树，而不是每个模块各扫一遍。
    // 它顺带给所有子项目加了 sonar 扩展，所以下面 subprojects {} 里能直接配置。
    alias(libs.plugins.sonarqube)
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

    // ── SonarQube 分析范围 ──────────────────────────────────────────────
    // 排除项写在 subprojects 里而不是根项目：官方文档给的共享配置写法就是
    // `subprojects { sonar { properties { ... } } }`，根项目那个 sonar 块只管
    // 「整个分析」的属性。逐模块设排除，才能保证每个模块都真的被排掉。
    //
    // 刻意**不写** sonar.sources / sonar.tests / sonar.java.libraries：
    // 插件会从 Gradle 对象模型里自动推导它们（sources ← sourceSets 的 srcDirs、
    // binaries ← 编译输出目录、libraries ← sourceSets.main.compileClasspath）。
    // 这三样以前要手工维护：sources/tests 得逐模块列 12 行，漏一个模块就静默
    // 不扫且不报错；libraries 得从 Gradle 缓存里翻 253 个 jar 并剔掉 sources/
    // javadoc。交给插件就没有「忘了更新」这回事，加新模块也自动纳入。
    sonar {
        properties {
            property(
                "sonar.exclusions",
                listOf(
                    // engine-protocol 的 protoc 生成代码被注册成了真正的 Kotlin
                    // srcDir（build/generated/source/proto/main/kotlin），
                    // 不排掉的话两万多行机器产物会把真实问题彻底淹没。
                    "**/build/**",
                    "**/.gradle/**",
                    // Gradle 脚本与配置：SonarQube 没有 Kotlin Script 解析器，
                    // 收进来只会被当「无法解析的文件」计噪声。
                    "**/*.kts",
                    "**/*.properties",
                    "**/*.yml",
                    "**/*.yaml",
                    "**/*.toml",
                    "**/*.md",
                ).joinToString(","),
            )
        }
    }
}

// ── SonarQube 分析配置（整个分析共用）────────────────────────────────────
//
// 官方文档明确：**SonarScanner for Gradle 不读 sonar-project.properties**。
// 原先那个文件已删除，配置全部收在这里 —— 留着它只会让人改了一个根本没人读的
// 文件，还以为生效了。这正是换用 Gradle 插件要避免的那类「静默失效」。
sonar {
    properties {
        // 项目标识。服务器上第一次扫描时如果这个 key 还不存在，会以当前用户为
        // owner 自动建一个同名项目，无需先在网页上点。
        property("sonar.projectKey", "langbiantianya_Sundays")
        property("sonar.projectName", "sundays")

        // Kotlin 语言版本。官方文档写「2.4.0 完全支持，1.3~2.3 支持」，
        // 本项目用的是 2.4.20。属性值按文档要求只写 X.Y 两位（不能写 2.4.20）。
        // 不写这一项，服务端会用自己的默认值，可能把 2.4 才有的语法判成解析失败。
        property("sonar.kotlin.source.version", "2.4")

        // 仓库全部是 UTF-8（见 desktopApp/tools/check-encoding.py 的体检项）。
        // 不写这一项，SonarQube 会按平台默认编码读，Windows 上会把中文注释读成
        // 乱码，进而报出一堆根本不存在的编码类问题。
        property("sonar.sourceEncoding", "UTF-8")

        // ── 刻意没有写的三项，以及为什么 ─────────────────────────────────
        //
        // 1. sonar.host.url / sonar.token（登录凭据）
        //    写死等于把本机地址和 token 提交进仓库。token 进了 git 历史就等于
        //    泄漏。由 desktopApp/tools/sonar-local-scan.py 从 SONAR_HOST_URL /
        //    SONAR_TOKEN 环境变量以系统属性注入（系统属性优先级高于构建脚本）。
        //
        // 2. sonar.java.libraries
        //    插件已自动从 sourceSets.main.compileClasspath 推导。手工维护的那份
        //    是从 Gradle 缓存里现算的 —— 同一个东西的更差实现。
        //
        // 3. sonar.coverageReportPaths
        //    本项目没接 JaCoCo，写一个指向不存在文件的路径会让每次扫描都报错。
        //    等真的接了覆盖率再补这一行。
    }
}

// ── 让 sonar 跑之前字节码已经存在 ────────────────────────────────────────
//
// 官方文档：「Task dependencies are not added automatically. The SonarScanner
// plugin enforces the correct order of tasks with mustRunAfter.」—— 光有顺序
// 不够，产出字节码的任务必须**真的执行过**，否则分析器拿到空的 classes 目录，
// 类型解析全面退化。
//
// 代价要说清楚：这一条会让 `./gradlew sonar` 先跑一遍全量编译（12 个模块，
// 本机约 3 分钟）。换来的是「扫的确实是当前这份源码编出来的字节码」。
// 刚编完只想重扫，用 `sonar -x classes -x testClasses -x jvmMainClasses
// -x jvmTestClasses` 跳过。
//
// 任务名为什么是四个而不是两个：`:shared` 是 Kotlin Multiplatform，它**没有**
// classes / testClasses 任务（实测报 "Cannot locate tasks that match
// ':shared:classes'"），只有 jvmMainClasses / jvmTestClasses；其余 11 个 JVM
// 模块反之。两种形态各取其名，谁存在算谁 —— 用 tasks.matching 而不是直接
// dependsOn 字符串路径，是因为 dependsOn 一个不存在的路径会直接让构建失败。
val sonarCompileTasks = setOf("classes", "testClasses", "jvmMainClasses", "jvmTestClasses")

// 根项目的脚本**先于**子项目的脚本求值，所以不能在配置阶段就遍历 subprojects
// 去数有哪些任务 —— 那时子项目的插件还没 apply，任务还不存在。
// 先把 Project 对象集固定下来，再用 provider 把「列出任务」这一步推迟到
// 任务图构建时（那时所有项目都配置完了）。
val allSubprojects = subprojects

tasks.named("sonar") {
    dependsOn(
        provider {
            allSubprojects.flatMap { sub ->
                sub.tasks.filter { it.name in sonarCompileTasks }.map { it.path }
            }
        },
    )
}
