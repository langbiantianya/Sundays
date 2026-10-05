package com.kxxnzstdsw.sundays.editor

/**
 * 造数沙箱注入的宿主函数 —— 补全候选的**权威清单**。
 *
 * ## 这批函数是什么
 *
 * 造数工作台的脚本不是裸 Lua：引擎的 `GenerateHandler` 在创建 Lua 状态后，
 * 额外向全局表塞进一批宿主函数（写库、随机造数），再把 `os` / `io` / `require` 等
 * 一批危险入口置 nil。用户在编辑器里能看到的函数，由这份清单描述。
 *
 * ## 为什么是「上下文相关」而不是进 [LuaLanguage]
 *
 * `insert` / `lastId` / `random_*` **只在造数沙箱里存在**。普通 Lua 编辑器
 * （纯文本预览、随手写段脚本）里调用它们会直接报 `attempt to call a nil value`。
 * 把它们塞进 `LuaLanguage.BUILTINS` 意味着**所有** Lua 补全都开始推荐不存在的函数 ——
 * 那不是提示，是误导。
 *
 * 所以这批候选只能由调用方**显式传入**（[com.kxxnzstdsw.sundays.editor.ui.CodeEditor]
 * 的 `extraCompletions`），造数工作台自己负责传。
 *
 * ## 与引擎的一致性由测试守住
 *
 * 本清单是静态的，不会随引擎运行而变化 —— 这对一批本项目自己定义、变更极低频的函数
 * 是合适的取舍（比新开一条 `SYSTEM.LIST_HELPERS` 协议路由便宜得多，且没有异步时序问题）。
 * 代价是「引擎加了函数、忘了加这里」，因此 `GenerateHandlerContractTest` 断言
 * 两侧集合一致：引擎侧一改，那个测试立刻变红。
 *
 * 新增函数时**两边都要改**：`GenerateHandler.registerHelpers` + 本清单。
 */
object GenerateHelpers {

    /**
     * 宿主函数候选（按展示顺序排列，**不要**在此处排序 —— 弹层按传入顺序展示）。
     *
     * `detail` 写成紧凑签名：弹层宽度有限，完整参数名塞不下，够用即可。
     */
    val completions: List<CompletionItem> = listOf(
        CompletionItem("insert", CompletionKind.BUILTIN, "insert(表名, {列=值})"),
        CompletionItem("lastId", CompletionKind.BUILTIN, "lastId() → 自增ID"),
        CompletionItem("random_int", CompletionKind.BUILTIN, "random_int(min, max)"),
        CompletionItem("random_float", CompletionKind.BUILTIN, "random_float(min, max)"),
        CompletionItem("random_string", CompletionKind.BUILTIN, "random_string(长度≤256)"),
        CompletionItem("random_name", CompletionKind.BUILTIN, "random_name()"),
        CompletionItem("random_email", CompletionKind.BUILTIN, "random_email()"),
        CompletionItem("random_phone", CompletionKind.BUILTIN, "random_phone()"),
        CompletionItem("random_date", CompletionKind.BUILTIN, "random_date(起, 止)"),
        CompletionItem("random_datetime", CompletionKind.BUILTIN, "random_datetime(起, 止)"),
        CompletionItem("random_time", CompletionKind.BUILTIN, "random_time()"),
        CompletionItem("random_uuid", CompletionKind.BUILTIN, "random_uuid()"),
        CompletionItem("random_enum", CompletionKind.BUILTIN, "random_enum({候选…})"),
    )

    /**
     * 被沙箱置 nil 的标准库入口 —— **仅作文档用途，本清单不据此抑制补全**。
     *
     * 记录在这里是为了让维护者知道沙箱边界（改 `applySandbox` 时同步这里）。
     *
     * **为什么不做抑制**：用户敲 `req` 却一个候选都看不到，会以为补全坏了；
     * 而候选出现了、运行时才报 `attempt to call a nil value`，用户至少能查到函数名。
     * 把错误暴露在能指认的位置，好过让它变成沉默。
     */
    val sandboxDisabled: List<String> = listOf(
        "os", "io", "debug", "package", "require",
        "loadfile", "dofile", "loadstring", "load",
        "rawget", "rawset", "rawequal",
        "setfenv", "getfenv", "newproxy",
    )

    /** 全部宿主函数名（测试与契约校验用）。 */
    val names: List<String> = completions.map { it.label }
}
