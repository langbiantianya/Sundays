package com.kxxnzstdsw.ipc

/** IPC 传输种类。 */
enum class IpcKind {
    /** TCP loopback（保留与旧 Wails 集成的兼容路径）。 */
    TCP,

    /** Unix Domain Socket（Linux / macOS / BSD）。 */
    UNIX,

    /** Windows 命名管道（Windows only）。 */
    PIPE,
}

/**
 * IPC 传输配置 — 由 [fromArgs] 从 CLI 参数构造。
 *
 * - 未传 `--ipc` 时按 OS 自动检测：Windows → [IpcKind.PIPE]；POSIX → [IpcKind.UNIX]
 * - 各字段默认值见 [fromArgs]
 */
data class IpcConfig(
    val kind: IpcKind,
    val tcpPort: Int = 50051,
    val tcpHost: String = LOOPBACK,
    val udsPath: String = "/tmp/idb-engine.sock",
    val pipeName: String = "idb-engine",
) {
    companion object {
        /**
         * TCP 默认绑定地址 —— **仅回环**。
         *
         * 引擎的 gRPC 端口没有鉴权：任何能连上的人都能执行任意 SQL（含 `SYSTEM.DISCONNECT`
         * 踢掉他人的连接）。绑 `0.0.0.0` 等于把数据库凭据直接暴露给整个网络，
         * 因此默认收紧到回环；确需跨机访问时用 `--host` 显式开启（用户知情选择）。
         */
        const val LOOPBACK = "127.0.0.1"
        val USAGE: String = """
            |IDB Engine — database compute engine (v2.9 dual-mode)
            |
            |Usage: java -jar idb-engine.jar [options]
            |
            |Modes:
            |  --mode <grpc|direct>  Run mode (default: grpc)
            |                         grpc   = start gRPC server (see --ipc below)
            |                         direct = in-process mode; load drivers/dialects,
            |                                 construct IdbEngine, block on main thread.
            |                                 Used by KMP Desktop when launched as standalone
            |                                 daemon, or for shell testing.
            |
            |Options (grpc mode only):
            |  --ipc <kind>         Transport kind: tcp | unix | pipe
            |                       Default: OS auto-detect (Windows=pipe, POSIX=unix)
            |  --port <int>         TCP port (only when --ipc=tcp). Default: 50051
            |  --host <addr>        TCP bind address (only when --ipc=tcp).
            |                       Default: 127.0.0.1 (loopback only).
            |                       NOTE: this gRPC port has no authentication — binding
            |                       0.0.0.0 exposes arbitrary SQL execution to the network.
            |  --uds-path <path>    Unix domain socket path (only when --ipc=unix)
            |                       Default: /tmp/idb-engine.sock
            |  --pipe-name <name>   Windows named pipe name (only when --ipc=pipe)
            |                       Default: idb-engine
            |  --help, -h           Show this help and exit
            |
            |Examples:
            |  java -jar idb-engine.jar                                 # grpc mode, OS-default IPC
            |  java -jar idb-engine.jar --mode grpc --ipc tcp --port 50051
            |  java -jar idb-engine.jar --mode grpc --ipc tcp --host 0.0.0.0 --port 50051
            |  java -jar idb-engine.jar --mode direct                   # in-process; KMP uses lib API
            |  java -jar idb-engine.jar --mode grpc --ipc unix --uds-path /var/run/idb.sock
            |  java -jar idb-engine.jar --mode grpc --ipc pipe --pipe-name idb-engine
        """.trimMargin()

        /**
         * 解析 CLI 参数。
         *
         * 不抛 checked exception：解析错误统一抛 [IllegalStateException]，
         * 由调用方（[com.kxxnzstdsw.server.IdbEngineServer]）捕获并以非零状态退出。
         */
        fun fromArgs(args: Array<String>): IpcConfig {
            var explicitKind: IpcKind? = null
            var tcpPort = 50051
            var tcpHost = LOOPBACK
            var udsPath = "/tmp/idb-engine.sock"
            var pipeName = "idb-engine"

            var i = 0
            while (i < args.size) {
                when (val a = args[i]) {
                    "--help", "-h" -> {
                        print(USAGE)
                        kotlin.system.exitProcess(0)
                    }
                    // `--mode <grpc|direct>` 由 IdbEngineServer.parseMode 独立解析（要先于本函数决定入口），
                    // 但它同样出现在 argv 里 —— 必须跳过，否则 `--mode grpc` 会被当成未知参数直接报错退出。
                    "--mode" -> {
                        args.getOrNull(i + 1) ?: error("--mode requires a value (grpc|direct)")
                        i += 2
                    }
                    "--ipc" -> {
                        val v = args.getOrNull(i + 1)
                            ?: error("--ipc requires a value (tcp|unix|pipe)")
                        explicitKind = parseKind(v)
                        i += 2
                    }
                    "--port" -> {
                        val v = args.getOrNull(i + 1)
                            ?: error("--port requires an integer value")
                        val parsed = v.toIntOrNull()
                            ?: error("--port expects an integer, got '$v'")
                        if (parsed !in 0..65535) {
                            error("--port must be in 0..65535, got $parsed")
                        }
                        tcpPort = parsed
                        i += 2
                    }
                    // 绑定地址：默认仅回环。gRPC 端口无鉴权，显式传 0.0.0.0 / :: 才对外暴露。
                    "--host" -> {
                        val v = args.getOrNull(i + 1)
                            ?: error("--host requires a bind address (e.g. 0.0.0.0)")
                        if (v.isBlank()) error("--host expects a non-empty bind address")
                        tcpHost = v
                        i += 2
                    }
                    "--uds-path" -> {
                        udsPath = args.getOrNull(i + 1)
                            ?: error("--uds-path requires a path value")
                        i += 2
                    }
                    "--pipe-name" -> {
                        pipeName = args.getOrNull(i + 1)
                            ?: error("--pipe-name requires a name value")
                        i += 2
                    }
                    else -> error("Unknown argument: '$a' (try --help)")
                }
            }

            // OS 自动检测（用户未显式传 --ipc）
            val kind = explicitKind ?: autoDetectKind()

            return IpcConfig(
                kind = kind,
                tcpPort = tcpPort,
                tcpHost = tcpHost,
                udsPath = udsPath,
                pipeName = pipeName,
            )
        }

        private fun autoDetectKind(): IpcKind {
            val os = System.getProperty("os.name", "").lowercase()
            return if (os.contains("win")) IpcKind.PIPE else IpcKind.UNIX
        }

        private fun parseKind(raw: String): IpcKind = when (raw.trim().lowercase()) {
            "tcp"  -> IpcKind.TCP
            "unix" -> IpcKind.UNIX
            "pipe" -> IpcKind.PIPE
            else   -> error("Invalid --ipc value '$raw' (expected: tcp | unix | pipe)")
        }
    }
}