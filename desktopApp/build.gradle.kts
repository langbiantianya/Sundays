import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    // 直接模式：Compose UI 与引擎同 JVM，通过 IdbEngine facade 直接调用
    // 不走 gRPC / 子进程 / IPC transport —— 详见 engine/README.md §Dual-Mode Architecture
    implementation(project(":engine"))

    implementation(compose.desktop.currentOs)
    // 使用版本目录直连依赖：compose.material3 访问器已废弃（Gradle 10 移除）
    implementation(libs.compose.material3)
    implementation(libs.kotlinx.coroutinesSwing)

    // :engine 以 implementation 声明 protobuf/grpc，不传递给消费方编译类路径。
    // 集成层需要 typed proto 类型（ConnectionConfig / SystemTestConnectionResponse / Response）
    // 才能调用 facade 的 Direct 模式 API —— 见 engine/README.md §Dual-Mode Architecture。
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin.lite)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.kxxnzstdsw.sundays.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.kxxnzstdsw.sundays"
            packageVersion = "1.0.0"
        }
    }
}