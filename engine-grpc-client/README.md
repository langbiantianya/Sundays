# engine-grpc-client — gRPC 调用模块

[`EngineClient`](../engine-protocol/README.md) 接口的**跨进程 gRPC 实现**。

本模块只依赖 `:engine-protocol`（契约 + 消息 + stub），**不依赖 `:engine`** —— 客户端进程不会拖进 Hadoop / POI / LuaJIT / 方言插件等引擎实现依赖。服务端依赖**仅在测试期引入**。

> **当前版本**：v2.15

## 模块结构

| 文件 | 内容 |
|---|---|
| [`GrpcEngineClient.kt`](./src/main/kotlin/com/langbiantianya/client/grpc/GrpcEngineClient.kt) | `EngineClient` 的 gRPC 实现 —— **逐方法行为与错误语义以该文件 KDoc 为权威** |
| [`GrpcClientConfig.kt`](./src/main/kotlin/com/langbiantianya/client/grpc/GrpcClientConfig.kt) | 端点配置 + 解析 + `ChannelBuilder` |
| [`GrpcEngineClientTest.kt`](./src/test/kotlin/com/langbiantianya/client/grpc/GrpcEngineClientTest.kt) | 端到端（真实 gRPC server + 真实 H2） |
| [`GrpcClientConfigTest.kt`](./src/test/kotlin/com/langbiantianya/client/grpc/GrpcClientConfigTest.kt) | 端点解析边界 |

## 用法

```kotlin
import com.langbiantianya.client.EngineClient
import com.langbiantianya.client.grpc.GrpcClientConfig
import com.langbiantianya.client.grpc.GrpcEngineClient

GrpcEngineClient.connect(GrpcClientConfig.fromTarget("localhost:50051")).use { client: EngineClient ->
    val resp = client.invoke(connection) {
        category = Category.SCHEMA
        action = Action.LIST
        schemaRequest = SchemaRequest.newBuilder()
            .setList(schemaListRequest { level = "database" })
            .build()
    }
}
```

`connect()` 建 channel 并由本客户端**持有**（`ownsChannel = true`），`close()` 负责关；另有 `wrap(channel)` 包装**外部**已建好的 channel（如共享连接或测试注入的 in-process channel）。`use` / `close()` 幂等，可以重复调用。

对端先启动引擎进程：

```bash
java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051
```

## 端点格式

`GrpcClientConfig.fromTarget(raw)` 接受：

| 写法 | 传输 | 说明 |
|---|---|---|
| `localhost:50051` | TCP | 裸 `host:port`；支持 IPv6 字面量 `[::1]:50051` |
| `tcp://127.0.0.1:50051` | TCP | 显式 scheme |
| `unix:///tmp/idb.sock` | UNIX | `unix://` 的 authority 段为空，路径从第三个 `/` 开始 —— 解析时会去掉多余的 `//`，否则会拿 `///var/...` 去连 socket |
| `pipe:idb-engine` | PIPE | Windows 命名管道 |

无法识别的格式**在连接前**就抛 `IllegalArgumentException`（消息里列出支持的写法），而不是产生一个连不上的 channel。`GrpcClientConfig.DEFAULT` = `localhost:50051`，与服务端默认 TCP 端口一致。Linux 上 TCP / UDS 优先 epoll、否则回退 NIO，与服务端 `UnixSocketIpcTransport` 的降级策略一致。

## 跨进程连接生命周期

客户端进程里**没有任何连接池** —— 池建在引擎进程内。因此连接生命周期必须走线上路由，这也是接口能在远程场景成立的前提：

| 调用 | 路由 | 说明 |
|---|---|---|
| `testConnection(config)` | `SYSTEM.TEST_CONNECTION` | 引擎端建（或复用）HikariCP 池并做 `isValid(5)` 校验。拿不到 `system` 负载时（传输失败）返回 `ok=false` + `error`，与同进程实现同形而非抛异常。 |
| `disconnect(config)` | `SYSTEM.DISCONNECT` | 释放该配置在引擎进程内的全部连接池。幂等：首次 `true`，无活跃池时 `false`（不是错误）。 |
| `close()` | —— | 关闭 channel：先 `shutdown()` 并等待 3s，未完成则 `shutdownNow()`；随后回收本模块创建的 Netty `EventLoopGroup`（Netty 不会替调用方关闭外部传入的 group）。 |

其余语义刻意与本地实现对齐：

- **流式分帧** —— 直接转交 `IdbEngineCoroutineStub.handle()` 的 `Flow<Response>`；每次 `handle` 是一次独立 RPC，取消 `collect` 即取消底层调用。
- **错误帧** —— 传输层故障（引擎未启动 / channel 已关 / UDS 不存在）以 gRPC `StatusException` 抛进 Flow，统一 `catch` 成 `success=false, error=<message>` 的终止帧。否则「换个传输就炸」。
- **界面语言** —— 出站请求经 `withLanguageTag` 盖上 BCP-47 标签；独立进程里 `ThreadLocal` 跨不过去，语言只能走 proto。

## 测试

```bash
./gradlew :engine-grpc-client:test
```

`GrpcEngineClientTest` 起**真实 gRPC 服务端**（`NettyServerBuilder.forPort(0)` + `IdbEngineImpl`，端口由 OS 分配，无探测竞态）打**真实 H2**，覆盖：远程连接初始化（两种形态）、**契约平价**（同一 `Request` 经 gRPC 与同进程处理，`Response` 列表逐帧相等）、流式 SELECT 分帧、远程连接释放的幂等、传输层故障 → `ok=false` 而非抛异常、`close` 幂等。

`GrpcClientConfigTest` 覆盖端点解析边界：IPv6、Unix 绝对路径、空 / 畸形输入。

## 跨链接

| 文档 | 内容 |
|---|---|
| [`../engine-protocol/README.md`](../engine-protocol/README.md) | `EngineClient` 接口契约 + proto 协议 |
| [`../engine/README.md`](../engine/README.md) | 引擎实现与 gRPC 服务端启动参数 |
| [`../engine/ARCHITECTURE.md`](../engine/ARCHITECTURE.md) | IPC Transport SPI（服务端侧） |
| [根 `../ARCHITECTURE.md`](../ARCHITECTURE.md) | 整体架构与模块导航 |
