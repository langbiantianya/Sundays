# `i18n/` — 六语言文案模块

> 六种界面语言的键枚举、译文表、简繁判定与复数规则。上层只写 `str(StrKey.XXX)`，不关心译文从哪来。
> 22 个源文件：7 个主文件 + `tables/` 下 12 份译文表 + 2 个测试类。
>
> **本文档的形态**：**大纲**。取值 API、语言判定、复数规则的**完整论述在源码 KDoc 上** ——
> 本文只回答「这个模块为什么存在、怎么用、加语言要动哪几处」。

---

## 1. 为什么单独建这个模块

文案表要同时喂给两个方向完全相反的消费者：**`:shared`** 渲染界面文案；**`:engine`** 抛出的错误消息也要上屏
（导入 / 导出失败、方言报错）。而 `:engine` 看不到 `:shared`，`:shared` 也看不到 `:engine`；`:api` 虽然两边
都能看到，但它是**方言 SPI 契约**，塞几百条界面文案进去会污染那个模块的职责边界。

**`:i18n` 是这两个方向的唯一交集**，因此它是**零 project 依赖**的叶子模块：内部不 import 任何 project 的类，
只依赖 Kotlin stdlib。它用 plain `kotlin("jvm")` 而不是 KMP —— plain JVM 能被 KMP 消费，反之不行（`:shared` 是
KMP，`:engine` / `:api` / 各 `dialect-*` 是纯 `kotlin-jvm`，两边唯一的公共类型只有 JVM 类型）。

| 主文件 | 职责 |
|---|---|
| [`AppLanguage.kt`](./src/main/kotlin/com/langbiantianya/i18n/AppLanguage.kt) | 语言枚举（tag / 母语名 / 别名）、简繁判定、`fromTagOrNull` 三步匹配、复数规则（`PluralForm`） |
| [`StrKey.kt`](./src/main/kotlin/com/langbiantianya/i18n/StrKey.kt) | 普通文案键（按语义分区排列） |
| [`PluralStrKey.kt`](./src/main/kotlin/com/langbiantianya/i18n/PluralStrKey.kt) | 复数文案键 |
| [`Strings.kt`](./src/main/kotlin/com/langbiantianya/i18n/Strings.kt) | 单语言文案表、占位符替换、`StringsRegistry` |
| [`LanguageScope.kt`](./src/main/kotlin/com/langbiantianya/i18n/LanguageScope.kt) | 语言优先级链的持有者 + 非组合上下文入口 `Msg` |
| [`Texts.kt`](./src/main/kotlin/com/langbiantianya/i18n/Texts.kt) / [`Plurals.kt`](./src/main/kotlin/com/langbiantianya/i18n/Plurals.kt) | 普通 / 复数表的语言分派（各一处**无 `else`** 的 `when`） |
| [`tables/`](./src/main/kotlin/com/langbiantianya/i18n/tables/) | 12 份译文表（六语言 × 普通 / 复数） |

---

## 2. 支持的语言

| 枚举 | BCP-47 tag | 母语名 | 说明 |
|---|---|---|---|
| `ZH_HANS` | `zh-Hans` | 简体中文 | 默认语言（也是退化目标）。额外接受 `zh` / `zh-CN` / `zh-SG` |
| `ZH_HANT` | `zh-Hant` | 繁體中文 | 额外接受 `zh-TW` / `zh-HK` / `zh-MO` |
| `EN` | `en` | English | |
| `RU` | `ru` | Русский | 复数三态（ONE / FEW / MANY） |
| `JA` | `ja` | 日本語 | |
| `DE` | `de` | Deutsch | |

落盘值就是 BCP-47 tag（`AppLanguage.persistedValue()`）。简繁判定为什么取「或」、裸 `zh` 为什么必须是别名而不是
tag、`fromTagOrNull` 的三步匹配、英德复数为什么用 `count == 1` —— 全部见
[`AppLanguage.kt`](./src/main/kotlin/com/langbiantianya/i18n/AppLanguage.kt) 的 KDoc。

---

## 3. 取文案的四个入口

| 入口 | 位置 | 什么时候用 |
|---|---|---|
| `str(key)` / `str(key, vararg args)` / `plural(key, count, vararg args)` | [`shared/.../ui/LocalStrings.kt`](../shared/src/commonMain/kotlin/com/langbiantianya/sundays/ui/LocalStrings.kt) —— `@Composable`，顶层由 `ProvideStrings(language)` 供应 | UI 层渲染界面文案；切语言只重组读它的那些节点 |
| `Msg.text(key)` / `Msg.format(key, vararg args)` / `Msg.plural(...)` | [`LanguageScope.kt`](./src/main/kotlin/com/langbiantianya/i18n/LanguageScope.kt) | 非组合上下文：引擎错误、方言报错、日志。`Msg` 不依赖 Compose，所以 `:engine` 侧能用 |
| `LanguageScope.withLanguage(language) { … }` | 同上 | 线程内临时切换，块结束自动还原 |
| `StringsRegistry.of()` / `systemDefault()` / `all()` | [`Strings.kt`](./src/main/kotlin/com/langbiantianya/i18n/Strings.kt) | 直接拿表（测试用）；六张表 `by lazy`，进程内只构造一次 |

占位符是自制的 `{0}` `{1}`、由 `Strings.format()` 自己替换（不用 `String.format` / `MessageFormat` 的理由见
[`Strings.kt`](./src/main/kotlin/com/langbiantianya/i18n/Strings.kt) 的 KDoc）；缺译时降级链最后一档返回**键名**，
界面上能立刻看出漏的是哪个键。

---

## 4. 语言优先级链

```
线程级 (ThreadLocal)  →  进程级 (AtomicReference)  →  系统 Locale 探测
```

- **线程级**：给引擎 handler 用。[`EngineLanguageContext`](../engine/src/main/kotlin/com/langbiantianya/engine/EngineLanguageContext.kt)
  这个 `ThreadContextElement` 适配器把语言装进协程上下文元素，`RequestDispatcher` 的 flow 用 `flowOn(...)` 搬运。
- **进程级**：`main.kt` 的 `LaunchedEffect(appearance.language)` 调 `LanguageScope.setProcessLanguage(...)`，
  让非 UI 线程（引擎内部线程池）也能拿到当前语言。
- **系统探测**：`AppLanguage.detectSystemLanguage()`。

**跨进程传递语言**：客户端在 `RequestOptions.language`（proto 字段 4）里带上 BCP-47 tag，
`IdbEngine.handle()` / `GrpcEngineClient.handle()` 统一用 `withLanguageTag(...)` 盖上。`RequestDispatcher`
**只在客户端真的填了 language 时才装上下文元素** —— 不填就不装，免得顶掉客户端没打算指定的服务端默认语言。

---

## 5. 新增一种语言的完整步骤

1. 在 [`AppLanguage.kt`](./src/main/kotlin/com/langbiantianya/i18n/AppLanguage.kt) 加枚举项（`tag` + `nativeName`，别名放 `extraTags`），并在同文件补该语言的复数规则。
2. 在 [`StrKey.kt`](./src/main/kotlin/com/langbiantianya/i18n/StrKey.kt) 对应的语义分区加键。
3. 在 [`tables/`](./src/main/kotlin/com/langbiantianya/i18n/tables/) 新建 `Texts<语言>.kt` 与 `Plurals<语言>.kt`。
4. 补 [`Texts.kt`](./src/main/kotlin/com/langbiantianya/i18n/Texts.kt) 与 [`Plurals.kt`](./src/main/kotlin/com/langbiantianya/i18n/Plurals.kt)
   里的 `when` 分支 —— **没有 `else`，编译器会把漏掉的语言报成「不完整」**，漏不掉。
5. 跑 `./gradlew :i18n:test`，`StringsIntegrityTest` 全绿。

> 六份表**刻意拆成六个独立文件**：翻译改动不会让整张表变成一个大 diff，六个语言互相不干扰，并行的翻译任务也能各写各的文件（见 [`Texts.kt`](./src/main/kotlin/com/langbiantianya/i18n/Texts.kt) KDoc）。

---

## 6. 测试

| 测试类 | 守什么 |
|---|---|
| [`AppLanguageTest.kt`](./src/test/kotlin/com/langbiantianya/i18n/AppLanguageTest.kt) | locale → 语言判定、`fromTagOrNull` 三步匹配、复数规则在 `0/1/2/5/11/21/22/25/100/101/111` 上的落类 |
| [`StringsIntegrityTest.kt`](./src/test/kotlin/com/langbiantianya/i18n/StringsIntegrityTest.kt) | **维护多份译文表的主要防线**：全语言全键覆盖、占位符下标集合跨语言一致、繁中 / 日语不许出现简体字形（各有白名单）、拉丁 / 西里尔译文不许整条照抄简体、无空白值 |

> **按文字体系分开校验，别一刀切。** 最初的规则「非中文表一律不许出现汉字」对日语误报 30+ 条 ——
> 日语本来就大量用汉字（確定 / 削除 / 編集 / 保存 / 設定 全是地道日语）。

---

语言状态为什么要放进 `AppearanceState` 见 [`../shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md)；
客户端装配与运行方式见 [`../desktopApp/README.md`](../desktopApp/README.md)。
