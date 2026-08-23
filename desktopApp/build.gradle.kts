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
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "com.kxxnzstdsw.idb_app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "com.kxxnzstdsw.idb_app"
            packageVersion = "1.0.0"
        }
    }
}