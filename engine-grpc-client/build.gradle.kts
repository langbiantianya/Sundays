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
