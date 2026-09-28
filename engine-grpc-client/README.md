# engine-grpc-client — gRPC 调用模块

**v2.15 新增** —— [`EngineClient`](../engine-protocol/README.md) 接口的**跨进程 gRPC 实现**。

本模块只依赖 `:engine-protocol`（契约 + 消息 + stub），**不依赖 `:engine`** —— 客户端进程不会拖进 Hadoop / POI / LuaJIT / 方言插件等引擎实现依赖。

> **当前版本**：v2.15

---

## 模块结构

```
engine-grpc-client/
├── build.gradle.kts
├── README.md
└── src/
    ├── main/kotlin/com/kxxnzstdsw/client/grpc/
    │   ├── GrpcEngineClient.kt       # EngineClient 的 gRPC 实现
    │   └── GrpcClientConfig.kt       # 端点配置 + 解析 + ChannelBuilder
    └── test/kotlin/com/kxxnzstdsw/client/grpc/
        ├── GrpcEngineClientTest.kt   # 端到端（真实 gRPC server + 真实 H2）
        └── GrpcClientConfigTest.kt   # 端点解析边界
```

---

## 用法

```kotlin
import com.kxxnzstdsw.client.EngineClient
import com.kxxnzstdsw.client.grpc.GrpcClientConfig
import com.kxxnzstdsw.client.grpc.GrpcEngineClient

val client: EngineClient = GrpcEngineClient.connect(GrpcClientConfig.fromTarget("localhost:50051"))
try {
    val resp = client.invoke(connection) {
        category = Category.SCHEMA
        action = Action.LIST
        schemaRequest = SchemaRequest.newBuilder()
            .setList(schemaListRequest { level = "database" })
            .build()
    }
} finally {
    client.close()
}
```

对端先启动引擎进程：

```bash
java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051
```

---

## 端点格式

`GrpcClientConfig.fromTarget(raw)` 接受：

| 写法 | 传输 | 说明 |
|---|---|---|
| `localhost:50051` | TCP | 裸 `host:port`；支持 IPv6 字面量 `[::1]:50051` |
| `tcp://127.0.0.1:50051` | TCP | 显式 scheme |
| `unix:///tmp/idb.sock` | UNIX | `unix://` 的 authority 段为空，路径从第三个 `/` 开始 —— 解析时会去掉多余的 `//` |
| `pipe:idb-engine` | PIPE | Windows 命名管道 |

无法识别的格式**在连接前**就抛 `IllegalArgumentException`（消息里列出支持的写法），而不是产生一个连不上的 channel。

`GrpcClientConfig.DEFAULT` = `localhost:50051`，与服务端默认 TCP 端口一致。

---

## 与本地实现的行为对齐

抽象的价值在于「换实现不改调用代码」。以下语义在 `GrpcEngineClient` 上被刻意对齐：

| 语义 | 处理方式 |
|---|---|
| **流式分帧** | 直接转交 `IdbEngineCoroutineStub.handle()` 的 `Flow<Response>`；每次 `handle` 是一次独立 RPC，取消 `collect` 即取消底层调用 |
| **错误帧** | 传输层故障（引擎未启动 / channel 已关 / UDS 不存在）会以 gRPC `StatusException` 抛进 Flow —— 统一 `catch` 成 `success=false, error=<message>` 的终止帧，与 `RequestDispatcher` 的约定一致 |
| **连接初始化** | `testConnection` 走 `SYSTEM.TEST_CONNECTION`，池建在**引擎进程**内 |
| **连接释放** | `disconnect` 走 `SYSTEM.DISCONNECT`（v2.15 新增路由），幂等 |
| **close** | 幂等：先 `shutdown()` + 等待 3s，未完成则 `shutdownNow()`；随后回收本模块创建的 Netty `EventLoopGroup`（Netty 不会替调用方关闭外部传入的 group） |

`testConnection` 拿不到 `system` 负载时（传输失败）返回 `ok=false` + `error` 文本，与 `SystemHandler.testConnection` 自身的 catch 分支同形。

---

## 测试

```bash
./gradlew :engine-grpc-client:test    # 21 项
```

`GrpcEngineClientTest` 起**真实 gRPC 服务端**（`NettyServerBuilder.forPort(0)` + `IdbEngineImpl`，端口由 OS 分配，无探测竞态）打**真实 H2**，覆盖：

1. 远程连接初始化（`testConnection` / 仅 JDBC URL 两种形态）
2. **契约平价** —— 同一个 `Request` 分别经 gRPC 与同进程 `IdbEngine` 处理，`Response` 列表逐帧相等
3. 流式 SELECT：每行一帧 + `end=true` 终止帧 + 帧 id 一致
4. 远程连接释放：首次 `disconnect=true`，第二次 `false`
5. 传输层故障 → `ok=false` 而非抛异常
6. `close` 幂等

`GrpcClientConfigTest` 覆盖端点解析边界：IPv6、Unix 绝对路径、空/畸形输入。

---

## 依赖

```kotlin
dependencies {
    api(project(":engine-protocol"))     // 契约 + 消息 + stub
    implementation(libs.grpc.netty.shaded)  // UDS 需要 Netty 的 DomainSocketChannel
}
```

服务端依赖（`engine`、方言插件、JDBC 驱动）**仅测试期引入**。

---

## 跨链接

| 文档 | 内容 |
|---|---|
| [`../engine-protocol/README.md`](../engine-protocol/README.md) | `EngineClient` 接口契约 + proto 协议 |
| [`../engine/README.md`](../engine/README.md) | 引擎实现与 gRPC 服务端启动参数 |
| [`../engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | IPC Transport SPI（服务端侧） |
| [根 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | 整体架构与模块导航 |
