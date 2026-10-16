# `i18n/` — 六语言文案模块

> 键枚举、语言判定、复数规则、六份译文表、降级链。
> 上层只管 `str(StrKey.XXX)`，不用关心译文从哪来。

## 支持的语言

| 枚举 | BCP-47 tag | 母语名 | 说明 |
|---|---|---|---|
| `ZH_HANS` | `zh-Hans` | 简体中文 | 默认语言。额外接受 `zh` / `zh-CN` / `zh-SG` |
| `ZH_HANT` | `zh-Hant` | 繁體中文 | 额外接受 `zh-TW` / `zh-HK` / `zh-MO` |
| `EN` | `en` | English | |
| `RU` | `ru` | Русский | 复数三态（ONE / FEW / MANY） |
| `JA` | `ja` | 日本語 | |
| `DE` | `de` | Deutsch | |

落盘值就是 BCP-47 tag（`AppLanguage.persistedValue()`）。

---

## 1. 为什么单独建模块，而不是放进 `api/`

文案表要同时喂给两个方向完全不同的消费者：

- **`:shared`** —— UI 层渲染界面文案
- **`:engine`** —— 引擎抛出的错误消息会上屏（导入失败、导出失败、方言报错）

而 `:engine` 看不到 `:shared`，`:shared` 看不到 `:engine`。`:api` 虽然两边都能看到，但它是**方言 SPI 契约**，塞 600 条界面文案进去会污染那个模块的职责边界。

`:i18n` 是这两个方向的唯一交集，所以它被建成一个**零 project 依赖**的叶子模块：

```
i18n  ──api──▶ shared
  │
  └───────────api──▶ engine / engine-grpc-client / api / dialect-*
```

`:i18n` 内部**不 import 任何 project 的类**，只依赖 Kotlin stdlib。这样它可以被任何一个模块无环地依赖。

### 为什么是 plain `kotlin("jvm")` 而不是 KMP

`:shared` 是 KMP（当前只落地了 jvm target），而 `:engine` / `:api` / 各 `dialect-*` 是纯 `kotlin-jvm`。
两边唯一的公共类型就是 JVM 类型，所以 `:i18n` 用 plain JVM jvm——**plain jvm 能被 KMP 消费，反之不行**。

### 为什么没有 `jvmToolchain(17)`

本机没装 JDK 17，foojay 自动下载在当前网络下拉不动。写死 toolchain 会让整个任务图配置不出来。
`:i18n` 刻意不声明 toolchain，跟随 Gradle 的 JVM 默认值，与 `:api` / `:engine` 保持一致。

---

## 2. 键：枚举而非字符串

`StrKey`（438 个成员）/ `PluralStrKey`（31 个成员）是**枚举**，不是字符串常量。

理由与项目既有的「封闭集合」哲学一致（见 `shared/.../ui/UiChrome.kt` 顶部注释）：

- 键拼错 → **编译期**报错，而不是运行到那个界面才白屏
- 键没译文 → 由 `StringsIntegrityTest` 拦下
- 新增语言 / 新增键 → `Texts.kt` 里的 `when` 没有 `else`，编译器会报「不完整」，漏不掉

`StrKey` 按语义分区排列（通用动作 / 导航 / 连接管理 / 浏览树 / SQL 工作台 / 设置页 / 引导页 / 引擎报错 / 方言……），
新增键请插入到对应分区，不要堆在文件末尾。

---

## 3. 取文案的四种姿势

### Composable 上下文（UI 层）

```kotlin
import com.langbiantianya.sundays.ui.str
import com.langbiantianya.sundays.ui.plural

Text(str(StrKey.CANCEL))
Text(str(StrKey.CONFIRM_DELETE_CONNECTION, connection.name))   // 占位符按顺序传
Text(plural(PluralStrKey.ROWS_COUNT, rowCount, rowCount))
```

这三个都是 `@Composable @ReadOnlyComposable`，包在 `LocalStrings`（`staticCompositionLocalOf`）里。
顶层用 `ProvideStrings(language) { … }` 供应，文案切换只重组读它的那些节点。

### 非组合上下文（引擎、方言、日志）

```kotlin
import com.langbiantianya.i18n.Msg

throw IllegalArgumentException(Msg.format(StrKey.DUCKDB_NO_ON_DELETE, table, ref))
Msg.text(StrKey.EXPORT_FAILED)
Msg.plural(PluralStrKey.ROWS_COUNT, n, n)
```

`Msg` 走 `LanguageScope.currentStrings()`，读的是**线程上下文 → 进程默认 → 系统探测**这条优先级链。

### 线程内临时切换

```kotlin
LanguageScope.withLanguage(AppLanguage.EN) { … }   // 块结束自动还原
```

### 直接拿表

```kotlin
StringsRegistry.of(AppLanguage.RU)      // 拿到某个语言的 Strings 实例
StringsRegistry.systemDefault()          // 系统语言
StringsRegistry.all()                    // 六份，测试用
```

六张表是 `by lazy` 懒加载的，进程内只构造一次。

---

## 4. 语言优先级链

`LanguageScope.currentLanguage()` 的取值顺序：

```
线程级 (ThreadLocal)  →  进程级 (AtomicReference)  →  系统 Locale 探测
```

- **线程级**：给引擎 handler 用。`EngineLanguageContext`（`engine/` 里的 `ThreadContextElement` 适配器）
  把语言装进协程上下文元素，`RequestDispatcher` 的 flow 用 `flowOn(...)` 搬运，
  协程换线程时语言跟着走。
- **进程级**：`main.kt` 的 `LaunchedEffect(appearance.language)` 调 `LanguageScope.setProcessLanguage(...)`，
  让非 UI 线程（引擎内部线程池）也能拿到当前语言。
- **系统探测**：`AppLanguage.detectSystemLanguage()`。

> 客户端在 `RequestOptions.language`（proto 字段 4）里带上 BCP-47 tag；
> `IdbEngine.handle()` / `GrpcEngineClient.handle()` 统一用 `withLanguageTag(...)` 盖上。
> `RequestDispatcher` **只在客户端真的填了 language 时才装**上下文元素 —— 不填就不装，
> 免得把客户端没打算指定的服务端默认语言给顶掉。

---

## 5. 简体 / 繁体判定

`AppLanguage.fromLocale(locale)` 的繁体判定取**或**：

```kotlin
locale.script == "Hant"  ||  locale.country in setOf("TW", "HK", "MO")
```

只看 script 会漏掉 `zh-TW`（很多系统只给地区码不给 script）；只看地区码会漏掉 `zh-Hant-SG`。
两个判据取或，两边都不满足才算简体。

`yue`（粤语）/ `nan`（闽南语）归**繁体**而不是回落简体——它们的书写系统本来就是繁体。

地区码（`Locale.getCountry()`）不参与语言判定：`de-AT` 仍是德语，`en-SG` 仍是英语。

### `fromTagOrNull` 的三步匹配

```
1. tag 精确匹配          "ja"      → JA
2. extraTags 精确匹配    "zh-TW"   → ZH_HANT
3. tag 前缀 + 后紧跟 '-'  "ja-JP"  → JA
```

第 3 步的「后紧跟 `-`」是必须的，否则 `zh-Hant-TW` 会因为 `zh` 是 `ZH_HANS` 的 alias
而被第 2 步抢走、判成简体。

**裸 `zh` 必须是 alias 而不是 tag**，理由同上：`zh-Hant-TW` 的前缀是 `zh`，如果 `zh` 是
`ZH_HANS` 的 tag，前缀规则就会把繁体判成简体。

匹配不上返回 `null`，调用方负责回落（见下）。

---

## 6. 复数规则

`AppLanguage.pluralForm(count)` 返回 `PluralForm`：

| 语言 | 类别 | 判据 |
|---|---|---|
| `RU` | `ONE` | `n % 10 == 1 && n % 100 != 11` |
| `RU` | `FEW` | `n % 10 in 2..4 && n % 100 !in 12..14` |
| `RU` | `MANY` | 其余（`n % 10 == 0`、`n % 100 in 11..14`、…） |
| `EN` / `DE` | `ONE` | `count == 1` |
| `EN` / `DE` | `OTHER` | 其余 |
| `ZH_HANS` / `ZH_HANT` / `JA` | `OTHER` | 只有一种 |

**英德用 `count == 1` 而不是 `n % 10 == 1`**：英语里 21 张桌子是 21 张桌子（`tables`），
不是「单数变位」；德语同理。俄语必须双重判据，否则 `11`、`21` 会落错类。

复数表的值里所有语言都用**同样的占位符集合**（`{0}` `{1}` `{2}`），
只是语序不同——俄语/德语把数量词放在词尾，所以译文里 `{0}` 的位置和简中不一样。

---

## 7. 占位符：为什么不用 `String.format`

占位符是自制的 `{0}` `{1}`，由 `Strings.format()` 自己做替换：

```kotlin
private val PLACEHOLDER = Regex("""\{(\d+)}""")
```

**不要用 `java.text.MessageFormat`**：它把单引号当转义符，译文里出现英文的所有格
（`user's`）会被吃掉一个字符，而且双写单引号 `''` 才会还原，翻译者不可能知道。

也不要预先 `String.format` 好再塞进表里——那样占位符的下标顺序就被语言绑死了。

---

## 8. 降级链

```
当前语言的译文  →  简体中文的译文  →  键名本身
```

最后一档返回键名（`CANCEL` 而不是空串或 `??CANCEL??`）：漏译时在界面上能**立刻看出是哪个键**，
总比白屏或空白强。

`StringsRegistry` 构造时会把每张表缺失的键**回填**成简体中文的值，
所以运行期查表永远命中，返回类型不需要处理 `null`。

---

## 9. 测试

`i18n/src/test/`：

- **`AppLanguageTest.kt`**（13 项）—— locale → 语言的判定、`fromTagOrNull` 三步匹配、
  复数规则在 `0/1/2/5/11/21/22/25/100/101/111` 上的落类。

- **`StringsIntegrityTest.kt`**（25 项）—— 这是维护六份表的主要防线：
  - 六语言**全键覆盖**，键集合完全一致
  - 占位符**下标集合**跨语言一致（不只比个数，`{0}`↔`{1}` 整体错位也要抓出来）
  - 拉丁/西里尔语言（英/德/俄）**不许出现汉字残留**
  - 繁中/日语**不许出现简体字形**（两张表：`SIMPLIFIED_ONLY_ZH_HANT` 全量、
    `SIMPLIFIED_ONLY_JA` = 全量 − `VALID_IN_JAPANESE`）
  - 拉丁/西里尔译文**不许整条照抄简体**
  - 复数类别按各语言规则齐备
  - 无空白值

> **按文字体系分开校验，别一刀切。** 最初的规则是「非中文表一律不许出现汉字」，
> 对日语误报了 30+ 条 —— 日语本来就大量用汉字（確定/削除/編集/保存/設定 全是地道日语）。
> 繁体表和日语表因此各自有一张白名单表。

---

## 10. 新增一条文案的完整流程

1. 在 `StrKey.kt` 对应的语义分区加一个枚举成员
2. 在 `TextsZhHans.kt` 补简体译文（其余五份先不补，`StringsIntegrityTest` 会红，这是预期的）
3. 调用点改成 `str(StrKey.新键)`
4. 其余五份表补译文 —— **五个 worker 可以并行，各写各的文件**
5. `.\gradlew.bat :i18n:test` 全绿

> 六份表**刻意拆成六个独立文件**：任何一次翻译改动都不会让整个表变成一个大 diff，
> 六个语言互相不干扰，审阅时能看清「德语这轮改了哪几条」，
> 并行的多个翻译任务也能各写各的文件而不冲突。
