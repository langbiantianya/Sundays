plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

// 刻意**不**声明 `jvmToolchain(…)` / `sourceCompatibility` —— 与 :api / :engine 保持一致，
// 直接用 Gradle 运行时自己的 JVM。写死 toolchain 会让「本机没装那个版本 + foojay 拉不动」
// 的机器直接配不出任务图，而文案表是纯 Kotlin 源码，对字节码版本没有任何要求。

// ============================================================================
// 这个模块**刻意不依赖任何东西**
// ============================================================================
//
// 它同时被两个方向完全不同的消费方使用：
//
// - `:shared` —— KMP（只有 jvm() 一个 target）的 Compose UI
// - `:engine` —— 纯 kotlin-jvm 的服务端进程，可独立于 UI 以 gRPC 方式部署
//
// 故这里既不能有 Compose（`:engine` 用不上，且会把 skiko 拖进服务端镜像），
// 也不能有 kotlinx.serialization（`:engine` 虽然有，但文案表不需要序列化：
// 六份表都是编译期常量，序列化只会在每次语言切换时多做一次无谓的解析）。
//
// 纯 Kotlin + JDK 是这个模块能同时喂给两边的**唯一**交集。
//
// 语言如何传到 `:engine` 侧见 `LanguageScope` 的 KDoc —— 那条链路由
// `RequestOptions.language`（proto 字段 4）承载，是跨进程时唯一走得通的路。

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
