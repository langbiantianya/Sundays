import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm")
    alias(libs.plugins.protobuf)
}

group = "com.kxxnzstdsw"
version = "1.0-SNAPSHOT"

// 协议层：proto 源 + 生成的 typed 消息 / gRPC stub + 调用层契约（EngineClient）。
// 不含任何业务逻辑、方言、连接池或导出实现 —— 因此可以被「引擎服务端」与「gRPC 客户端」
// 同时依赖而不产生循环依赖，也不会把 Hadoop / POI 等重依赖带给纯客户端。
dependencies {
    // api 而非 implementation：Request / Response / EngineClient 是本模块对外的公开契约，
    // 消费方（engine 服务端、engine-grpc-client 客户端、前端）编译期都需要这些类型。
    api(libs.protobuf.java)
    api(libs.protobuf.kotlin.lite)
    api(libs.grpc.stub)
    api(libs.grpc.protobuf)
    api(libs.grpc.kotlin.stub)
    api(libs.kotlinx.coroutines.core)
    api(libs.slf4j.api)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.35.1"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.83.1"
        }
        id("grpckt") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.5.0:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
                id("grpckt")
            }
            // 生成 Kotlin DSL（com.kxxnzstdsw.grpc.ColumnDefKt 等），业务层通过 columnDef { ... } 构造
            task.builtins {
                id("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
}
