# `dialect-contract/` — 连接表单契约模块

> 方言自描述「连得上要填什么」的那份声明。`:api` 声明，`:shared` 渲染，`:engine` 传递，`:desktopApp` 还原。
> 4 个源文件 + 1 个测试类。
>
> **本文档的形态**：**大纲**。类型语义与「为什么」的**完整论述在源码 KDoc 上** ——
> 本文只回答「这个模块为什么存在、谁依赖谁、加一个方言要动哪几处」。

---

## 1. 为什么单独建这个模块

连接表单的形状是**方言的知识**，而填表界面住在 `:shared`。问题是这两边彼此看不见：

| 模块 | 是什么 | 看得见对方吗 |
|---|---|---|
| `:api` | 方言插件 SPI（含 `java.sql.Connection`） | 看不见 `:shared` |
| `:shared` | Kotlin Multiplatform 的 UI 层 | 看不见 `:api`（`:api` 带 `java.sql`，UI 层依赖引擎 SPI 是层级倒置） |
| `:engine` | 引擎实现 | 看不见 `:shared` |
| `:desktopApp` | 桌面应用装配点 | 两边都看得见 |

所以「方言说形状、前端照着画」这件事需要一个**双方都依赖的第三方**。这与
[`:i18n`](../i18n/README.md) 是**同一个问题、同一个解法** —— 文案表当初也是为了
「两边都要用、但彼此看不见」才从 `:shared` 里下沉出来的。

> 💡 **仓库里已经有这个先例**，所以这里不需要发明第二套机制。

依赖方向是单向的：

```
:api ──────┐
           ├──▶ :dialect-contract ◀── :shared
:desktopApp┘
```

`:dialect-contract` 自己的依赖只有 `:i18n`（`ConnectionField.labelKey` 是 `StrKey`）与
kotlinx-serialization（只为 `AuthMethod` 一个枚举，见下）。

---

## 2. 里面有什么

| 文件 | 职责 |
|---|---|
| [`ConnectionFormSpec.kt`](./src/main/kotlin/com/langbiantianya/dialect/contract/ConnectionFormSpec.kt) | `ConnectionFormSpec`（一份 spec 多个 form）、`ConnectionForm`、`ConnectionField`、`FieldType`、`ConnectionFieldIds`、`ConnectionFormTypes` |
| [`AuthMethod.kt`](./src/main/kotlin/com/langbiantianya/dialect/contract/AuthMethod.kt) | 认证方式枚举（`PASSWORD` / `KERBEROS` / `NONE`）+ `fromName` |
| [`DialectConnectionSpec.kt`](./src/main/kotlin/com/langbiantianya/dialect/contract/DialectConnectionSpec.kt) | 一次连接尝试的**参数值**（host / port / database / properties）—— 与上面的「形状」是一对，方向相反 |
| [`StandardForms.kt`](./src/main/kotlin/com/langbiantianya/dialect/contract/StandardForms.kt) | 常用形状的构造器（客户端/服务端六格、嵌入式两格） |

### 为什么 spec 里有多个 form

同一个方言在不同**连接类型**下的表单形状是真的不同（H2 的内存库填库名、文件库填路径）。
硬塞进一份扁平的字段表，就只能取一个形状 —— 剩下那个连接类型要么显示错的标签，
要么得在渲染层再写一次 `when`（那就白做了）。所以 spec 里是 form 的列表，每份认一个连接类型。

连接类型用**字符串**而不是枚举：`:api` 与 `:shared` 各有一份 `ConnectionType` 枚举
（前者是引擎 SPI 的，后者是持久化格式的一部分），契约模块若再定义第三份，就是
**同一个概念三处定义** —— 那正是本仓库反复在消灭的东西。两边都用各自枚举的 `name`，
由 `desktopApp` 的契约测试钉住「同名集合一致」。

### 为什么只有 `AuthMethod` 是 `@Serializable`

它是 `ConnectionConfig` 的字段，要跟着 `connection.json` 落盘（v3 持久化格式的一部分），
所以必须可序列化 —— **枚举常量名就是序列化名**，改常量名会让存量连接读不出来。

其余类型**都不序列化**：它们走 proto 跨进程（proto 自带序列化），而且
`ConnectionField.labelKey` 持有 `StrKey`，而 `StrKey` 在 `:i18n` 里**刻意不带**
kotlinx-serialization（文案表是编译期常量，序列化只会在每次语言切换时多做一次无谓的解析）。

---

## 3. 加一个方言要动哪几处

1. **方言模块**里 override `DatabaseDialect.connectionForm`，声明自己的 form 列表。
   形状与内置方言相同就直接用 `StandardForms`（客户端/服务端、嵌入式）。
2. **字段的文案**用 `StrKey`（不是字面量）—— 新键要**六语言同步**（见
   [`:i18n` 的第 5 节](../i18n/README.md#5-新增一种语言的完整步骤)）。
3. **前端不需要改** —— 连接向导按 spec 渲染。前提是引擎的
   `SYSTEM.LIST_DRIVERS` 把它带过去（见 `engine/.../DialectInfoMapper.kt`）。

> ⚠️ **`DialectType` 仍是持久化格式的一部分**（`connection.json` 里存着方言名），
> 所以「新增方言零前端改动」在**枚举层面**不成立 —— 真正能零改动的是
> **表单字段形状**（由 spec 驱动）。

---

## 4. 测试

| 测试类 | 守什么 |
|---|---|
| [`ConnectionFormSpecTest.kt`](./src/test/kotlin/com/langbiantianya/dialect/contract/ConnectionFormSpecTest.kt) | 查表规则（「没声明」与「声明了空表单」要分得开）、认证方式的名字往返、**序列化名 = 常量名**（磁盘格式）、常用形状的字段清单与顺序 |

---

## 5. 相关文档

- 设计文档（连接管理器通用化）：[`../docs/analysis-mode/README.md`](../docs/analysis-mode/README.md) 的 §5
- 前端方言元数据（另一张表，与本模块**不重叠**）：[`../shared/ARCHITECTURE.md`](../shared/ARCHITECTURE.md)
- 装配点：[`../desktopApp/README.md`](../desktopApp/README.md)
