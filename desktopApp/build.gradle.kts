import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

dependencies {
    implementation(project(":shared"))
    // 调用层抽象：UI 只面向 EngineClient 接口编程（来自 :engine-protocol，经 :engine 以 api 传递）。
    // 两个实现都在装配点可选：
    //   :engine            → IdbEngine（同进程直调，默认；不启动子进程 / 不建 gRPC channel）
    //   :engine-grpc-client → GrpcEngineClient（跨进程 gRPC；-Dsundays.engine.endpoint=host:port 启用）
    // 选择逻辑见 main.kt 的 createEngineClient()。
    implementation(project(":engine"))
    implementation(project(":engine-grpc-client"))

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
    // FeatureWalkthroughTest 要在 **H2 与 SQLite 两个方言**上跑同一批走查用例:
    // 对象浏览的能力差异只有跨方言才暴露得出来（SQLite 无触发器 / 无函数，
    // 而 H2 三样都有）。driver 侧由 runtimeOnly(libs.sqlite) 在运行时提供。
    testImplementation(project(":dialect-sqlite"))
    // DialectSmokeTest 要在 **四个方言**上跑同一套冒烟（H2 / SQLite / MySQL / PostgreSQL），
    // 源码里要能 new 出这四个方言来注册。
    testImplementation(project(":dialect-mysql"))
    testImplementation(project(":dialect-postgresql"))
    // DuckDbFileSourceTest 要用 DuckDB 方言 + POI（造 .xlsx）与 duckdb_jdbc 驱动。
    // 方言模块把 POI 声明成 implementation —— 运行时在（随 runtimeOnly 传递进来），
    // 但测试**源码**要 import 它来造文件，故这里显式补一条 testImplementation。
    testImplementation(project(":dialect-duckdb"))
    testImplementation(libs.poi)
    testImplementation(libs.poi.ooxml)
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
