/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

import org.gradle.jvm.toolchain.JavaLanguageVersion
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
    // 界面语言与文案表注册表。main.kt 要把用户选定的语言同时喂给
    // ①界面（ProvideStrings）②同 JVM 内的引擎（LanguageScope.setProcessLanguage，
    // Direct 模式）③出站请求（Request.options.language，gRPC 模式）。
    // :shared 与 :engine 都依赖它，本模块直接引用而不是绕道 :shared，
    // 是为了不让「装配点依赖某个实现模块的传递依赖」这种写法扩散。
    implementation(project(":i18n"))
    // 方言**能力位**枚举（ANALYSIS_SOURCE / READ_ONLY_DIALECT / EMBEDDED_MODE …）。
    //
    // 界面要按能力位筛数据源（「哪些连接能进分析模式」），而能力位是**引擎侧**的声明
    // —— 前端自己写一份「MySQL/PG 可分析」的名单就是第二个真相源，两边迟早漂移。
    // 所以这里直接引用引擎用的同一个枚举：引擎下发什么，前端就按什么判。
    //
    // :api 是纯 JVM 模块（无 project 依赖形成环），本模块也是 JVM 桌面应用，引用无冲突。
    implementation(project(":api"))

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
    // src/test/kotlin/com/langbiantianya/sundays/ConnectionManagerFlowTest.kt
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
    // DuckDbFileSourceTest 要用 DuckDB 方言（造 .xlsx）与 duckdb_jdbc 驱动。
    testImplementation(project(":dialect-duckdb"))
    // POI —— **报表导出**要写 .xlsx（`ReportXlsxWriter`）。
    //
    // 为什么在这里**显式**声明，而不像以前那样靠 `dialect-duckdb` 传递：
    // 那边是**它**的需要（Excel 预转换），报表导出是**本模块**的需要 ——
    // 靠传递依赖用别人的库，等于把「本模块需要 POI」这件事藏了起来，
    // 而依赖一旦收紧就断，且断的时候报错指向「找不到类」，与真正的原因隔了三层。
    //
    // ⚠️ **打包运行时不受影响**：POI 早已随 `dialect-duckdb` 进了镜像，
    // 所以 jlink 的模块清单一个都不用加（见 nativeDistributions 的说明）。
    implementation(libs.poi)
    implementation(libs.poi.ooxml)
    testImplementation(project(":api"))
}

// 运行与打包用的 JDK —— 与根 build.gradle.kts 的 jvmToolchain 是**同一个版本**。
//
// 版本取自根构建（`jdkVersion`，全仓库唯一声明处）。
val jdkVersion = rootProject.extra["sundaysJdkVersion"] as Int

compose.desktop {
    application {
        mainClass = "com.langbiantianya.sundays.MainKt"

        // ------------------------------------------------------------------
        // 运行与打包用的 JDK —— 与根 build.gradle.kts 的 jvmToolchain 同版本。
        //
        // 为什么必须显式给：Compose 的 `javaHome` 默认指向 **Gradle daemon 的 JVM**，
        // 而 daemon 的 JDK 由「谁启动了 Gradle」决定（JAVA_HOME / org.gradle.java.home）——
        // 仓库无法声明。而编译目标已经被 jvmToolchain 钉在 25，两者脱钩就要出事：
        //
        //   · run：UnsupportedClassVersionError: class file version 69.0, 运行时只认到 65.0
        //   · 打包：用 21 的 jlink 去链接 25 的字节码 → 安装包**装得进去却启不了**
        //
        // 这个属性同时驱动三件事（compose-gradle-plugin 1.11.1 的源码）：
        //   · `run` task → exec.executable(javaExecutable(app.javaHome))
        //   · jlink     → jdkHome.set(app.javaHomeProvider)
        //   · jpackage  → javaHome.set(app.javaHomeProvider)
        //
        // 另外：设了它之后 Gradle 会从 executable 反推 toolchain，javaLauncher 自动跟随，
        // 不会再报「Toolchain from `executable` property does not match toolchain from
        // `javaLauncher` property」。
        // ------------------------------------------------------------------
        javaHome =
            javaToolchains
                .launcherFor {
                    languageVersion.set(JavaLanguageVersion.of(jdkVersion))
                }.get()
                .metadata
                .installationPath
                .asFile
                .absolutePath

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)

            // ------------------------------------------------------------------
            // 程序名：sundays（原来是 com.langbiantianya.sundays，跟包名走的那串）。
            //
            // 用户看到的是 exe、开始菜单快捷方式、控制面板「程序和功能」、macOS
            // 的 .app、Linux 的启动器 —— 一整串域名式名字。那是从代码包名直接漏
            // 到用户界面的，不是给人看的名字。
            //
            // ⚠️ 想「只改 Windows」是**做不到**的，这点试过：
            //   · packageName 是 nativeDistributions 级的，Dmg/Msi/Deb 共用；
            //   · javap 反编译 compose-gradle-plugin 1.11.1 确认
            //     WindowsPlatformSettings 根本没有 packageName 字段，
            //     而 macOS 与 Linux 的同名属性**写了也不生效** ——
            //     实测 macOS { packageName = "com.langbiantianya.sundays" }
            //     之后产出的仍是 sundays.app、app-image 仍是 app/sundays，
            //     build-macos.yml 与 build-flatpak.yml 当场双双变红。
            // 所以这里统一改名，把三个平台的产物名一起对齐。
            //
            // 改包名**不影响启动**：
            //   · mainClass 在上面显式写死了（com.langbiantianya.sundays.MainKt），
            //     不由 packageName 推导 —— 这是 Compose 历史上最容易踩的坑，
            //     这里先确认过才敢改；
            //   · 应用自己的源码包名（com.langbiantianya.*）是另一回事，
            //     与打包元数据无关，不受影响。
            //
            // 连带要跟着改的（都只认这一个名字）：
            //   · .github/scripts/assemble-flatpak-app.sh 的 LAUNCHER_NAME
            //     —— 它同时是启动器名与 jpackage 的 <packageName>.cfg 文件名；
            //   · build-macos.yml 里 .app 包名与 Contents/MacOS/ 下的启动器名；
            //   · build-flatpak.yml 里组装产物的启动器路径；
            //   · packaging/flatpak/*.json 里 manifest 的 command（应用 id 不动，
            //     那是 Linux 上的应用身份，按惯例保持反向域名）。
            // ------------------------------------------------------------------
            packageName = "sundays"
            packageVersion = "1.0.0"

            // ---------------------------------------------------------------
            // jlink 运行时的模块集。**少一个模块，打出来的包就起不来。**
            //
            // Compose 的默认值只有四个（dsl/JvmApplicationDistributions.kt）：
            //   java.base, java.desktop, java.logging, jdk.crypto.ec
            // jlink 顺着 requires 补齐后是 7 个：
            //   java.base java.datatransfer java.xml java.prefs
            //   java.desktop java.logging jdk.crypto.ec
            // 这对本项目**远远不够** —— 缺 java.sql（JDBC，数据库工具的命根子）、
            // 缺 java.naming（logback/slf4j 初始化就要它）等等。
            //
            // 症状极其有欺骗性：jlink 运行时是完整的、文件一个不缺、
            // 启动器也能建起 VM，但应用在 <clinit> 里就抛
            // NoClassDefFoundError，而打包出来的是**无控制台的 GUI 程序**，
            // jpackage 启动器只弹一句：
            //     Failed to launch JVM
            // 一句跟「模块缺了」毫无关系的话。
            // 换成便携版、换成另一台机器、换个安装目录，全都一样，
            // 因为问题从头到尾不在安装环节，而在链接出来的运行时里。
            //
            // 下面这份清单不是猜的，是 jdeps 按字节码算出来的：
            //   jdeps --multi-release 21 --ignore-missing-deps \
            //         --print-module-deps -R -cp "<app>\*" desktopApp-*.jar
            // 输出 14 个模块，减去 runtime 里已有的 7 个，得到下面这 12 个。
            // **升级依赖后要重算一遍** —— 新依赖引到哪个模块，jdeps 会知道，
            // 靠异常去撞则永远只会看到最后崩的那一个。
            // ---------------------------------------------------------------
            modules(
                "java.sql", // JDBC：MySQL / PostgreSQL / H2 / SQLite / DuckDB
                "java.naming", // logback + slf4j 的 LoggerFactory 初始化
                "java.management", // 部分驱动/诊断工具
                "java.instrument", // 字节码增强类库
                "java.compiler", // 运行时编译（脚本/规则引擎）
                "java.scripting", // 脚本引擎
                "java.rmi", // JDBC 驱动里对 RMI 的引用
                "java.security.jgss",
                "java.security.sasl",
                "java.xml.crypto",
                "jdk.jfr", // 运行期诊断
                "jdk.unsupported", // sun.misc 等被大量第三方库直接引用
            )

            windows {
                // ---------------------------------------------------------------
                // 快捷方式：默认一个都不建，必须显式开。
                //
                // 源码（compose-gradle-plugin 1.11.1 的 dsl/PlatformSettings.kt）：
                //   var shortcut: Boolean = false
                //   var menu: Boolean = false
                //       get() = field || menuGroup != null
                //
                // 两个默认值都是 false，于是 jpackage 拿不到 --win-shortcut 与
                // --win-menu，**MSI 里连 Shortcut 表都不生成**（实测：表清单里
                // 根本没有 Shortcut 这一项），安装完开始菜单与桌面自然空空如也。
                // 这不是 jpackage 的毛病，是 Compose 把 jpackage 的默认值原样
                // 透传下来了 —— 而 jpackage 本来就不替应用做这个决定。
                // ---------------------------------------------------------------

                // 桌面图标
                shortcut = true

                // 开始菜单。menu 的 getter 是 `field || menuGroup != null`，
                // 所以光给 menuGroup 也算开了；两个都写是为了让意图不必靠 getter 猜。
                menu = true
                // 不给 menuGroup 的话，开始菜单文件夹名会退回产品名
                // com.langbiantianya.sundays。
                menuGroup = "Sundays"

                // ------------------------------------------------------------------
                // UpgradeCode 钉死在改名前的那个值上。
                //
                // jpackage 默认按 packageName 推导 UpgradeCode，所以改了包名它就变
                // —— 后果是**新包不再覆盖旧安装，而是在旁边并排装一份**：控制面板
                // 里出现两个条目，旧的 exe 还在，用户得自己去「应用和功能」卸载。
                // 本仓库已经吃过一次这个亏（com.kxxnzstdsw → com.langbiantianya
                // 那次改名就并排装了），这次不想再来一遍。
                //
                // 这个值是从改名前那份 MSI 的 Property 表里读出来的，不是猜的：
                //   {1427B270-5AA8-3E6C-A0E1-02ED63A7160C}
                //
                // 钉住的额外好处：以后再改包名不会再动到升级链路，
                // 新包会正常覆盖安装、替换 exe、保留用户数据。
                // ------------------------------------------------------------------
                upgradeUuid = "1427B270-5AA8-3E6C-A0E1-02ED63A7160C"

                // ---------------------------------------------------------------
                // 图标：不给就用插件自带的 JetBrains 默认图标。
                //
                // configureJvmApplication.kt 里是
                //   packageTask.iconFile.set(win.iconFile.orElse(defaultResources.get { windowsIcon }))
                // —— 只有显式给了 iconFile 才用自己的，否则一律落到 default-icon-windows.ico。
                // 仓库里的 sundays.ico 从来没接上过（main.kt 的注释也提过
                // 「要用 iconFile 设图标」）。快捷方式的图标取自 exe，
                // 所以这一步和上面的 shortcut 是同一件事的两半：
                // 开了快捷方式却不给图标，用户看到的是 JetBrains 的默认图标。
                // ---------------------------------------------------------------
                iconFile = file("src/main/resources/icons/sundays.ico")
            }
        }
    }
}

/*
 * 可选的 `user.home` 覆盖，供**手动启动应用**时隔离配置目录：
 *
 * ```
 * .\gradlew.bat :desktopApp:run -PsundaysUserHome=C:\Temp\sundays-probe
 * ```
 *
 * ## 为什么需要它
 *
 * `ConnectionStorage` 写的是 `~/.config/sundays/connection.json`（含**明文口令**），
 * `OnboardingState` 也会写 `~/.config/sundays/settings.json`。手动跑一次应用就会碰到
 * 真实用户的连接配置 —— 自动化测试里那是要靠 `System.setProperty` 隔离的。
 *
 * 用 Gradle **属性**而不是写死路径：不传就完全不生效，CI 与开发机的默认行为不变。
 */
tasks.withType<JavaExec>().configureEach {
    providers.gradleProperty("sundaysUserHome").orNull?.let {
        systemProperty("user.home", it)
    }
}
