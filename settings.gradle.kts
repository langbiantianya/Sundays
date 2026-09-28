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