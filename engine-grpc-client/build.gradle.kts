plugins {
    kotlin("jvm")
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

// gRPC 调用模块 —— EngineClient 接口的跨进程实现。
// 只依赖 :engine-protocol（契约 + proto 消息 + gRPC stub），**不依赖 :engine**：
// 因此不会把 Hadoop / POI / LuaJIT / 方言插件等引擎实现依赖带进客户端进程。
dependencies {
    api(project(":engine-protocol"))
    // 界面语言与文案表。gRPC 模式下引擎在独立进程，语言只能经 `Request.options.language`
    // 传递，而客户端这一侧负责把它填上（见 GrpcEngineClient.handle）。
    // 零依赖的 :i18n 不会把任何引擎实现依赖带进客户端进程，与本模块的隔离意图一致。
    implementation(project(":i18n"))
    // UDS 传输需要 Netty shaded 的 DomainSocketChannel（与服务端 UnixSocketIpcTransport 对称）
    implementation(libs.grpc.netty.shaded)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    // 端到端测试需要真实 gRPC 服务端 + 真实方言：仅测试期引入
    testImplementation(project(":engine"))
    // DatabaseDialect SPI 接口（显式注册 H2 方言所需）
    testImplementation(project(":api"))
    testImplementation(project(":dialect-h2"))
    testImplementation(libs.h2)
    testRuntimeOnly(libs.logback.classic)
}

tasks.test {
    useJUnitPlatform()
}
