/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm")
    alias(libs.plugins.protobuf)
}

group = "com.langbiantianya"
version = "1.0-SNAPSHOT"

// 协议层：proto 源 + 生成的 typed 消息 / gRPC stub + 调用层契约（EngineClient）。
// 不含任何业务逻辑、方言、连接池或导出实现 —— 因此可以被「引擎服务端」与「gRPC 客户端」
// 同时依赖而不产生循环依赖，也不会把 Hadoop / POI 等重依赖带给纯客户端。
dependencies {
    // api 而非 implementation：Request / Response / EngineClient 是本模块对外的公开契约，
    // 消费方（engine 服务端、engine-grpc-client 客户端、前端）编译期都需要这些类型。
    api(libs.protobuf.java)
    api(libs.protobuf.kotlin.lite)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    api(libs.grpc.kotlin.stub)
    api(libs.kotlinx.coroutines.core)
    api(libs.slf4j.api)
}

// protoc 及其 gRPC 插件都是**按平台分发的原生可执行文件**。插件默认用 osdetector
// 按当前机器的 OS + 架构挑 classifier，产物坐标是
//     "$groupId:$artifactId:$version:$classifier@exe"
// 而 osdetector 在 Windows ARM64 上给出的 classifier 是 `windows-aarch_64` ——
// 这个 classifier **上游从来没有发布过**：
//   com.google.protobuf:protoc:4.35.1           → windows-x86_32 / windows-x86_64
//                                                   （ARM 版只有 linux-aarch_64 与 osx-aarch_64）
//   io.grpc:protoc-gen-grpc-java:1.83.1          → 同上
// 于是 CI 的 windows-11-arm 上直接挂在依赖解析：
//   Could not resolve all files for configuration
//   ':engine-protocol:protobufToolsLocator_grpc'
//
// 退回 x86_64 那份是安全的，因为 protoc 只是个**代码生成器**，吐出来的是
// 与平台无关的 Java / Kotlin 源码。插件自己的 GenerateProtoTask.getReleaseArtifacts
// 就是这么写的：「Giving this as an input to the task allows gradle to ignore the
// OS classifier and use cached outputs generated from different operating systems
// since the expectation is that different operating systems will produce the same
// generated code.」它甚至刻意把 OS classifier 排除在任务输入之外，好让不同 OS
// 之间复用同一份生成产物 —— 这条注释正好是本处做法的依据。
// Windows ARM64 能原生模拟 x64 程序，所以 x86_64 的 exe 直接跑得起来，
// 生成结果与在 x64 上跑完全一致。
//
// artifact 写成四段（group:artifact:version:classifier）时，
// ToolsLocator.resolveLocator 里 `classifier = classifier ?: osdetector.classifier`
// 会优先采用自带的 classifier，不再去问 osdetector。
// 非 ARM 机器走空串，仍由 osdetector 按实际平台挑，行为与改动前完全一致。
val onWindowsArm64: Boolean =
    System.getProperty("os.name").startsWith("Windows") &&
        System.getProperty("os.arch") == "aarch64"
val protocExeSuffix: String = if (onWindowsArm64) ":windows-x86_64@exe" else ""

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1$protocExeSuffix"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.83.1$protocExeSuffix"
        }
        // grpckt 是纯 jar（:jdk8@jar），本就与平台无关，不受这件事影响。
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            // 生成 Kotlin DSL（com.langbiantianya.grpc.ColumnDefKt 等），业务层通过 columnDef { ... } 构造
            task.builtins {
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
