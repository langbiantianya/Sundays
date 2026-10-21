/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

rootProject.name = "sundays"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":desktopApp")
include(":shared")
include(":i18n")
// 连接表单契约 —— 「方言连得上要填什么」的共享声明。
// 与 :i18n 同构：:api（声明）与 :shared（渲染）都依赖它，而彼此看不见对方。
include(":dialect-contract")
include("dialect-mysql")
include("dialect-postgresql")
include("dialect-h2")
include("dialect-duckdb")
include("dialect-sqlite")
include("api")
include("engine")

// 调用层抽象：engine-protocol 持有 proto 契约 + EngineClient 接口；
// engine 是其服务端实现，engine-grpc-client 是其 gRPC 客户端实现。
include(":engine-protocol")
include(":engine-grpc-client")
