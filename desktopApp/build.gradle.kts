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

    // 方言插件 + JDBC 驱动随应用类路径加载（Direct 模式不依赖外部 dialects/ drivers/ 目录）：
    // DialectLoader.loadFromDir 会先扫一遍应用类路径上的 SPI（ServiceLoader），再让 dialects/ 目录覆盖同名方言。
    // 缺了这些依赖，IdbEngine 将解析不出任何方言（"No dialect plugin matches JDBC URL"）。
    runtimeOnly(project(":dialect-mysql"))
    runtimeOnly(project(":dialect-postgresql"))
    runtimeOnly(project(":dialect-h2"))
    runtimeOnly(project(":dialect-duckdb"))
    runtimeOnly(project(":dialect-sqlite"))
    runtimeOnly(libs.mysql.connector)
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.duckdb)
    runtimeOnly(libs.sqlite)

    implementation(compose.desktop.currentOs)
    // 使用版本目录直连依赖：compose.material3 访问器已废弃（Gradle 10 移除）
    implementation(libs.compose.material3)
    // Material Icons Extended —— DatabaseBrowserScreen 用到的图标（Storage / Folder /
    // TableChart / Refresh / ChevronRight / ExpandMore / Close 等）
    implementation(libs.material.icons.extended)
    implementation(libs.kotlinx.coroutinesSwing)

    // :engine 以 implementation 声明 protobuf/grpc，不传递给消费方编译类路径。
    // 集成层需要 typed proto 类型（ConnectionConfig / SystemTestConnectionResponse / Response）
    // 才能调用 facade 的 Direct 模式 API —— 见 engine/README.md §Dual-Mode Architecture。
    implementation(libs.protobuf.java)
    implementation(libs.protobuf.kotlin.lite)

    implementation(libs.compose.uiToolingPreview)

    // Compose UI 测试（runComposeUiTest）+ JUnit4：连接管理流程端到端验证见
    // src/test/kotlin/com/kxxnzstdsw/sundays/ConnectionManagerFlowTest.kt
    testImplementation(libs.compose.uiTest)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.junit)
    // DatabaseBrowserFlowTest 烟雾测试用 H2 内存库 + H2 dialect 直接注册:
    // 把方言插件放到 testImplementation 是为了让测试源码可见 H2Dialect 构造器.
    // H2 JDBC 驱动已经在上面的 runtimeOnly(libs.h2) 提供运行时加载.
    testImplementation(project(":dialect-h2"))
    testImplementation(project(":api"))
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
