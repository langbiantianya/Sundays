/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.i18n

/**
 * 全应用文案的键枚举 —— 「说哪句话」的唯一权威清单。
 *
 * ## 它解决什么
 *
 * 界面里散落着几百句硬编码的中文。直接把它们替换成 `Msg.text(StrKey.XXX)` 之后，
 * 「这句话有没有对应的译文」就成了一个**可以由编译器回答**的问题：
 * 键拼错是编译错误，译文表缺一条是 [StringsIntegrityTest] 的断言失败，
 * 而不再是「界面上莫名其妙冒出一句中文」。
 *
 * ## 三条硬约定
 *
 * 1. **一个键 = 一句完整渲染出来的成品话。** 「共 N 行」是一个键，
 *    「共 \" 与 \" 行」不是两个键。把拼接拆开会让每种语言都要重新决定
 *    语序，那正是「整句交给译者」要避免的事。
 * 2. **带数量的句子进 [PluralStrKey]。** 中文只分 OTHER 一档，俄语要分三档，
 *    差别在**词形**而不在键。留在 StrKey 里的 `{0}` 一律是非名词性插值
 *    （文件名、路径、原始报错），那种句子换语言也不会变形。
 * 3. **语义命名，不是坐标命名。** 键名描述这句话**是什么**（[SETTINGS_TITLE]），
 *    不是它出现在哪一行。同一个词在两处含义不同时必须拆成两个键 ——
 *    中文恰好能一字不差地表达两种意思（「加载失败」既可以是状态也可以是面板标题），
 *    英文与俄语往往需要不同的词，那时它们会分道扬镳。
 *
 * ## 分组
 *
 * 下面的 `// ──── 区域名 ────` 分隔对应界面区域，只是给人看的；
 * 枚举本身是**扁平**的，调用方按 [StrKey] 取值，不依赖任何分组约定。
 *
 * ## 为什么成员不带构造参数
 *
 * 键是编译期常量，译文在 [com.langbiantianya.i18n.tables.textsZhHans] 那样的
 * `Map<StrKey, String>` 里。把中文挂在枚举上等于把两种语言焊死在同一个类型里，
 * 改一个键名要同时改六份表 —— 那正是本项目选择分离的原因。
 */
enum class StrKey {
    // ──────────────── 通用动作 ────────────────
    CANCEL,
    CONFIRM,
    DELETE,
    EDIT,
    CLOSE,
    BACK,
    RETRY,
    REFRESH,
    APPLY,
    BROWSE,
    CHOOSE,
    START_EXPORT,
    EXECUTE_ANYWAY,
    MARK_ALL_READ,
    CLEAR_NOTIFICATIONS,
    CLEAR_FILTER,
    MORE_ACTIONS,
    NEXT_STEP,
    PREV_STEP,
    SAVE,
    CLOSE_SHEET,
    CLOSE_SQL_SHEET,
    CLOSE_SCRIPT,
    CLOSE_TAB,
    CLOSE_NOTIFICATION_CENTER,
    OPEN_NOTIFICATION_CENTER,
    CLOSE_MEMORY_DETAIL,
    FORMAT_CODE,
    PREVIEW,
    DONE,
    FAILED,
    START_USING,

    // ──────────────── 导航 ────────────────
    NAV_CONNECTIONS,
    NAV_DATABASE,
    SETTINGS_TITLE,

    // ──────────────── 连接管理 ────────────────
    ADD_CONNECTION,
    ADD_CONNECTION_DIALOG_TITLE,
    NO_OPEN_CONNECTION,
    EMPTY_CONNECTION_HINT,
    DELETE_CONNECTION,
    CONFIRM_DELETE_CONNECTION,
    DELETE_CONNECTION_WARNING,
    DELETE_CONNECTION_NOTE,
    CONNECTION_LIST,
    QUICK_CONNECT,
    NEW_CONNECTION,
    NO_SAVED_CONNECTIONS,
    CONNECTION_STATUS,
    CONNECTION_INFO,
    NOT_CONNECTED,
    CONNECTING,
    CONNECTED,
    CONNECT_FAILED,
    CONNECTING_ELLIPSIS,
    CONNECT,
    MISSING_JDBC_URL,
    DISCONNECT,
    CONNECTION_MANAGER_TITLE,
    CONNECTION_MANAGER_HINT,
    QUICK_CONNECT_HINT,
    CONN_TYPE_CLIENT_SERVER,
    CONN_TYPE_IN_MEMORY,
    CONN_TYPE_EMBEDDED_OLAP,
    CONN_TYPE_FILE,
    BACK_WITH_ARROW,
    BASIC_INFO,
    CONNECTION_NAME,
    CONNECTION_NAME_HINT,
    DIALECT,
    DIALECT_MYSQL_DESC,
    DIALECT_POSTGRESQL_DESC,
    DIALECT_H2_DESC,
    DIALECT_DUCKDB_DESC,
    DIALECT_SQLITE_DESC,
    CONNECTION_TYPE,
    SELECT_CONNECTION_MODE,
    MODE_CLIENT_SERVER,
    MODE_EMBEDDED,
    MODE_UNKNOWN,
    MODE_CUSTOM_URL,
    DESC_NEEDS_HOST_PORT,
    DESC_EMBEDDED_IN_APP,
    DESC_IN_MEMORY_VOLATILE,
    DESC_SINGLE_FILE,
    DESC_CUSTOM_URL,
    CONNECTION_DETAILS,
    HOST,
    HOST_HINT,
    PORT,
    DATABASE_NAME,
    DATABASE_NAME_HINT,
    USERNAME,
    PASSWORD,
    JDBC_URL_DERIVED,
    FIELDS_PENDING,
    REQUIRED_FIELDS_HINT,
    DATABASE_FILE_PATH,
    DATABASE_FILE_PATH_HINT_H2,
    DATABASE_FILE_PATH_HINT_DUCKDB,
    DATABASE_FILE_PATH_HINT_SQLITE,
    DATABASE_FILE_PATH_LABEL,
    DATABASE,

    // ──────────────── 连接表单（方言自描述） ────────────────
    //
    // 这一组是「连接表单规格」（`dialect-contract` 模块的 `ConnectionFormSpec`）
    // 要用的文案键 —— 方言声明字段时给出**键**，而不是字面量。
    //
    // · `FIELD_JDBC_URL` —— JDBC URL 输入框的标签。之前是硬编码的英文字面量
    //   "JDBC URL"：它在六语言界面里是一处英文漏出（其余字段都有译文），
    //   入表之后顺带修掉。
    // · `FIELD_AUTH_METHOD` —— 认证方式下拉的标签。
    // · `AUTH_METHOD_*` —— 三种认证方式各自的显示名（账密 / Kerberos 票据 / 无认证）。
    FIELD_JDBC_URL,
    FIELD_AUTH_METHOD,
    AUTH_METHOD_PASSWORD,
    AUTH_METHOD_KERBEROS,
    AUTH_METHOD_NONE,

    // ──────────────── 连接表单 · 方言特有字段 ────────────────
    //
    // 这一组是「**方言特有**的连接参数」的标签 —— 主机 / 端口 / 库名 / 账密那些
    // 通用字段的键在上面（HOST / PORT / …），这一组是**每个方言不一样**的那些
    // （数仓那边的服务名、Kerberos 主体、keytab 路径…）。
    FIELD_SERVICE_NAME,
    FIELD_KERBEROS_PRINCIPAL,
    FIELD_KEYTAB_PATH,

    TEST_AND_SAVE,
    CONNECTION_SUMMARY,
    TESTING,
    TEST_CONNECTION,
    CONNECT_SUCCESS,
    CONNECT_FAILED_WITH_REASON,
    NAME,
    DIALECT_SUMMARY,
    TYPE_LABEL,
    HOST_SUMMARY,
    USER,
    TARGET,
    WIZARD_STEP,
    NEW_CONNECTION_LABEL,
    NO_FILE_SPECIFIED,
    IN_MEMORY_DB_LABEL,

    // ──────────────── 数据库浏览 · 树 ────────────────
    BROWSER_TITLE,
    NOT_CONNECTED_HINT,
    LOADING_DATABASES,
    LOAD_FAILED,
    NO_DATABASES,
    NO_DATABASES_HINT,
    LOAD_DATABASES_FAILED,
    LOAD_TABLES_FAILED,
    NO_REASON_FROM_ENGINE,
    COPY_NAME,
    COPY_COLUMN_REF,
    COPY_REF_NAME,
    COPY_CREATE_TABLE_DDL,
    TREE_LOAD_FAILED,
    TREE_LOADING,
    TREE_EMPTY,
    TREE_OBJECTS_LOADING,
    OBJECT,
    NONE_PAREN,
    OPEN_TABLE,
    COLLAPSE_TABLE_DETAIL,
    EXPAND_TABLE_DETAIL,
    COLUMN,
    INDEX,
    FOREIGN_KEY,
    COPY_COLUMN,
    NOT_NULL,
    NO_OPEN_TABLE,
    NO_OPEN_TABLE_HINT,
    SEARCH,
    SORT,
    SORT_WITH_CLAUSE,
    HIDE_FILTER,
    FILTER,
    EXPORT,
    WHERE_CONDITION_HINT,
    INVALID_CONDITION,
    NO_SORT,
    SORT_FIRST_COL_ASC,
    SORT_FIRST_COL_DESC,
    CONFIRM_DANGEROUS_OPERATION,
    DANGEROUS_NO_UNDO,
    CROSS_DATABASE_ROWS,
    CATALOG,
    TABLE,
    VIEWS,
    TRIGGERS,
    ROUTINES,
    DB_TYPE_HEADER,
    DB_TABLE_HEADER,
    DB_TABLE_TYPE_HEADER,
    DB_TABLE_BREADCRUMB,
    COPIED,
    COPY_FAILED,
    REFERENCE_NAME,
    ENGINE_NO_DDL,
    CREATE_TABLE_DDL,
    DB_NAME_PLACEHOLDER,
    CONNECT_FAILED_PREFIX,
    READ_TABLE_FAILED,

    /** 库级对象（视图 / 触发器 / 过程）加载失败时的行内前缀 —— `视图：<原始报错>`。 */
    OBJECT_LOAD_ERROR_PREFIX,

    // ──────────────── 数据库浏览 · 预览与导出 ────────────────
    EXPORT_FAILED_WITH_REASON,
    PREVIEW_FAILED,
    EXPORT_RESULT,
    FORMAT,
    OUTPUT_DIR_ABSOLUTE,
    FILE_NAME,
    CHOOSE_FILE,
    TARGET_TABLE_NAME,
    EXPORT_SQL_EDITABLE,
    EXPORT_SQL_PREFILLED_HINT,
    PREVIEWING,
    PREVIEW_FIRST_100_ROWS,
    PREVIEW_SELECT_ONLY_HINT,
    PREVIEW_EMPTY_HINT,
    ZERO_ROWS,
    EXPORTING,
    CONNECTING_ENGINE,
    RUN_IN_BACKGROUND,
    LOADING,
    ERROR_PREFIX,
    READ_FAILED,
    NO_DATA,
    NO_DATA_HINT,
    FORMAT_SQL_INSERT,
    FORMAT_EXCEL,
    FORMAT_PARQUET,

    /**
     * 纯 ASCII 的格式名，**各语言原样保留**。与 [PALETTE_WIN2000] 同理：它没有对应的
     * 简体源串，但仍占一个键，好让 `ExportFormat` 的 label 字段类型统一，
     * 渲染侧不必写「这个枚举要不要查表」的分叉。
     */
    FORMAT_CSV,
    FORMAT_JSON_LINES,
    TASK_DONE_DETAIL,
    TASK_FAILED_DETAIL,
    EXPORT_COMPLETE_FILE,
    EXPORT_FAILED_FILE,
    EXPORT_FAILED,
    UNKNOWN_REASON,
    SQL_EMPTY,
    OUTPUT_DIR_REQUIRED,
    FILE_NAME_REQUIRED,
    EXPORT_TABLE_NAME_REQUIRED,
    EXPORT_TIMEOUT,
    EXPORT_NO_FILE_REPORTED,
    NO_PRIMARY_KEY_EDIT_DISABLED,
    ROW_NOT_ON_PAGE,
    PRIMARY_KEY_NOT_IN_ROW,
    UPDATE_FAILED,
    READ_ONLY_WRITE_BLOCKED,
    PREVIEW_WRITE_REJECTED,

    // ──────────────── SQL 工作台 ────────────────
    SQL_WORKBENCH,
    GENERATE_WORKBENCH,
    WORKBENCH_NOT_CONNECTED,
    TASK,
    BACK_TO_PREVIEW,
    DEFAULT_CATALOG,
    NEW_SQL_SHEET,
    RENAME_CURRENT_TAB,
    RENAME_TAB,
    NO_EDITABLE_SQL_SHEET,
    NO_EDITABLE_SQL_SHEET_HINT,
    READ_ONLY,
    MULTI_STATEMENT,
    COMMIT,
    ROLLBACK,
    TRANSACTION,
    STOP,
    EXECUTING,
    EXECUTE_SQL,
    EXECUTE_FAILED,
    NO_SQL_EXECUTED,
    NO_SQL_EXECUTED_HINT,
    SQL_EXECUTION_FAILED,
    SHEET_ALREADY_IN_TRANSACTION,
    BEGIN_TRANSACTION_NO_RESPONSE,
    BEGIN_TRANSACTION_FAILED,
    NO_SESSION_ID,
    COMMIT_TRANSACTION_FAILED,
    ROLLBACK_TRANSACTION_FAILED,
    NOT_IN_TRANSACTION,
    OP_NO_ENGINE_RESPONSE,
    ENGINE_NO_RESPONSE,
    ENGINE_NO_RESULT,
    UNKNOWN_ERROR,
    NO_RETURN_RESULT,
    CANNOT_STOP,

    /** 工作台顶部的 schema 副标题 —— `schema: public`。`schema` 是 SQL 标准术语，不译。 */
    SCHEMA_LABEL,

    // ──────────────── 造数工作台 ────────────────
    SCRIPT,
    SCRIPT_DEFAULT_NAME,
    SCRIPT_NTH_NAME,
    LUA_VERSION,
    NEW_GENERATE_SCRIPT,
    NO_EDITABLE_SCRIPT,
    NO_EDITABLE_SCRIPT_HINT,
    GENERATING,
    RUN_GENERATE,
    GENERATE_FAILED,
    GENERATE_FAILED_REASON,

    GENERATE_SCRIPT_EMPTY,
    NO_DATA_INSERTED,

    // ──────────────── 造数实时日志 ────────────────

    /** 日志面板的标题。 */
    GENERATE_LOG_TITLE,

    /** 日志为空时的标题。 */
    GENERATE_LOG_EMPTY_TITLE,

    /** 日志为空时的说明 —— 告诉用户「跑一次造数就会出现」。 */
    GENERATE_LOG_EMPTY_HINT,

    /**
     * 一条正常日志行的文本。
     *
     * 占位符：`{0}` 脚本下标（1 起）· `{1}` 脚本总数 · `{2}` 目标表 ·
     * `{3}` 该脚本内的行号 · `{4}` 这一行的列值文本。
     */
    GENERATE_LOG_LINE,

    /** 失败行的文本 —— `{0}` 是引擎给的失败原因原文。 */
    GENERATE_LOG_ERROR,

    /** 日志超出上限、丢掉了最早的那些行 —— `{0}` 丢掉多少行。 */
    GENERATE_LOG_DROPPED,

    // ──────────────── 通知中心 ────────────────
    NOTIFICATION_CENTER,
    NOTIFICATIONS_TITLE,
    NO_NOTIFICATIONS_HINT,

    // ──────────────── 后台任务 ────────────────
    BACKGROUND_TASKS,
    CLEAR_FINISHED,
    NO_BACKGROUND_TASKS_HINT,

    // ──────────────── 内存与运行时 ────────────────
    HEAP_MEMORY,
    JVM_MEMORY,
    ENGINE_SYSTEM_INFO,
    HEAP_USED,
    HEAP_ALLOCATED,
    HEAP_MAX,
    HEAP_FREE,
    MEMORY_USAGE,

    // ──────────────── 设置页 ────────────────
    PERSONALIZATION,
    APPEARANCE_HINT,
    COLOR_THEME,
    THEME_MODE,
    QUICK_TOGGLE_THEME,
    NEXT_THEME_LEVEL,
    UI_DENSITY,
    COMPACT_MODE,
    COMPACT_HINT,
    COMPACT_HINT_ONBOARDING,
    APPEARANCE_ONBOARDING,
    REOPEN_ONBOARDING_HINT,
    REOPEN_ONBOARDING,
    SYSTEM_INFO,
    SYSTEM_INFO_HINT,
    AUTO_REFRESH,
    NOT_LOADED_YET,
    LOADING_SYSTEM_INFO,
    LOAD_SYSTEM_INFO_FAILED,
    JVM_VERSION,
    JVM_VENDOR,
    JVM_NAME,
    OPERATING_SYSTEM,
    SYSTEM_ARCH,
    AVAILABLE_PROCESSORS,
    JVM_HEAP_USED,
    JVM_HEAP_ALLOCATED,
    JVM_HEAP_MAX,
    JVM_HEAP_FREE,
    UPTIME,
    PROCESS_PID,

    // ──────────────── 主题 ────────────────
    THEME_FOLLOW_SYSTEM,
    THEME_FOLLOW_SYSTEM_DESC,
    THEME_LIGHT,
    THEME_LIGHT_DESC,
    THEME_DARK,
    THEME_DARK_DESC,
    THEME_TOGGLE_SYSTEM_TO_LIGHT,
    THEME_TOGGLE_LIGHT_TO_DARK,
    THEME_TOGGLE_DARK_TO_SYSTEM,
    PALETTE_BLUE_GRAY,
    PALETTE_BLUE_GRAY_DESC,
    PALETTE_CYBERPUNK,
    PALETTE_CYBERPUNK_DESC,
    PALETTE_BILI_PINK,
    PALETTE_BILI_PINK_DESC,

    /**
     * 专有名词，**各语言原样保留**。它没有对应的简体源串（纯 ASCII 的产品名不进表），
     * 但仍占一个键 —— 这样 `ThemePalette` 的 label 字段类型统一，渲染侧不必写
     * 「这个枚举要不要查表」的分叉。译文里填的也是 `Win2000`。
     */
    PALETTE_WIN2000,
    PALETTE_WIN2000_DESC,
    PALETTE_WINXP,
    PALETTE_WINXP_DESC,
    AUTO_REFRESH_OFF,
    AUTO_REFRESH_10S,
    AUTO_REFRESH_5S,
    AUTO_REFRESH_2S,
    AUTO_REFRESH_1S,

    // ──────────────── 语言 ────────────────

    /**
     * 语言选择器的分组标题。
     *
     * 注意**语言名本身不占键** —— 那是 [AppLanguage.nativeName]，恒以母语书写
     * （`简体中文` `English` `Deutsch`…），任何语言下都显示母语名才是对的。
     * 一个看不懂 `Deutsch` 的人无法用搜索找到它，所以不能把语言名翻成当前界面语言。
     */
    LANGUAGE,

    /** 引导页里的说明（与设置页的措辞不同：引导页说「现在就挑」，设置页说「随时改」）。 */
    LANGUAGE_HINT_ONBOARDING,

    /** 设置页里的说明。 */
    LANGUAGE_HINT_SETTINGS,

    // ──────────────── 引导页 ────────────────
    WELCOME,
    ONBOARDING_HINT,
    ONBOARDING_SETTINGS_HINT,
    PALETTE_CARD_HINT,

    // ──────────────── 数据表 ────────────────
    EMPTY_DATA,
    PER_PAGE,
    FIRST_PAGE,
    PREV_PAGE,
    NEXT_PAGE,
    LAST_PAGE,
    DETAIL,
    DETAIL_HINT,
    PRIMARY_KEY,
    CELL_EDIT_NO_COLUMN_KEY,
    CELL_EDIT_SAME_VALUE,
    EDIT_PENDING_COUNT,
    EDIT_APPLY,
    EDIT_DISCARD,
    EDIT_APPLIED,
    EDIT_DISCARDED,
    EDIT_NOTHING_PENDING,
    EDIT_APPLY_ROLLED_BACK,
    EDIT_BEGIN_FAILED,
    EDIT_ROLLBACK_FAILED,
    EDIT_BLOCKED_READ_ONLY,
    EDIT_COLUMN_READ_ONLY,
    EDIT_REASON_PRIMARY_KEY,
    EDIT_REASON_BINARY,
    EDIT_COLUMN_META_FAILED,
    EDIT_SET_NULL,
    EDIT_CLEAR_NULL,
    SELECT_ALL_ROWS,
    SELECT_ROW,
    EDIT_DELETE_ROWS,
    EDIT_DELETE_NONE,
    EDIT_DELETED_MARKED,
    EDIT_INSERT_ROWS,
    EDIT_INSERT_TITLE,
    EDIT_INSERT_ADD_ROW,
    EDIT_INSERT_REMOVE_ROW,
    EDIT_INSERT_CONFIRM,
    EDIT_INSERT_ALL_NULL,
    DELETE_FAILED,
    INSERT_FAILED,
    EDIT_INSERT_DISABLED,
    EDIT_CAPABILITIES_MISSING,
    EDIT_DELETE_CONFIRM_HINT,
    TABLE_EDIT_STRUCTURE,
    TABLE_NEW,
    TABLE_DELETE,
    TABLE_DIALOG_NEW_TITLE,
    TABLE_DIALOG_EDIT_TITLE,
    TABLE_DIALOG_NAME,
    TABLE_DIALOG_LOADING,
    TABLE_DIALOG_LOAD_FAILED,
    TABLE_DIALOG_COLUMNS,
    TABLE_DIALOG_FOREIGN_KEYS,
    TABLE_COLUMN_NAME,
    TABLE_COLUMN_TYPE,
    TABLE_COLUMN_LENGTH,
    TABLE_COLUMN_PRIMARY_KEY,
    TABLE_COLUMN_NULLABLE,
    TABLE_COLUMN_AUTO_INCREMENT,
    TABLE_COLUMN_DEFAULT,
    TABLE_COLUMN_ADD,
    TABLE_COLUMN_REMOVE,
    TABLE_FK_ADD,
    TABLE_FK_REMOVE,
    TABLE_FK_NAME,
    TABLE_FK_COLUMNS,
    TABLE_FK_REF_TABLE,
    TABLE_FK_REF_COLUMNS,
    TABLE_FK_ON_DELETE,
    TABLE_FK_ON_UPDATE,
    TABLE_FK_ACTION_NO_ACTION,
    TABLE_FK_ACTION_RESTRICT,
    TABLE_FK_ACTION_CASCADE,
    TABLE_FK_ACTION_SET_NULL,
    TABLE_FK_REF_TABLE_HINT,
    TABLE_DIALOG_SAVE,
    TABLE_DDL_NO_ROLLBACK,
    TABLE_STRUCTURE_SAVED,
    TABLE_STRUCTURE_SAVED_CHANGES,
    TABLE_STRUCTURE_NO_CHANGE,
    TABLE_CREATED,
    TABLE_SAVE_FAILED,
    TABLE_CREATE_FAILED,
    TABLE_DELETE_FAILED,
    TABLE_DELETED,
    TABLE_DELETE_CONFIRM,
    TABLE_DELETE_CONFIRM_HINT,
    TABLE_BLOCKED_READ_ONLY,
    TABLE_NAME_EMPTY,
    TABLE_NO_COLUMNS,
    TABLE_COLUMN_NAME_EMPTY,
    TABLE_COLUMN_NAME_DUPLICATE,
    TABLE_COLUMN_TYPE_EMPTY,
    TABLE_PK_NOT_NULL,
    TABLE_FK_INCOMPLETE,
    TABLE_COLUMN_TYPE_CUSTOM,
    TABLE_FK_COMPLEX_READONLY,
    PAGE_SIZE_ALL,

    // ──────────────── 补全 ────────────────
    COMPLETION_KEYWORD,
    COMPLETION_FUNCTION,
    HELPER_INSERT,
    HELPER_LAST_ID,
    HELPER_RANDOM_STRING,
    HELPER_RANDOM_DATE,
    HELPER_RANDOM_DATETIME,
    HELPER_RANDOM_ENUM,

    // ──────────────── 危险操作 ────────────────
    RISK_DROP,
    RISK_TRUNCATE,
    RISK_DELETE_NO_WHERE,
    RISK_UPDATE_NO_WHERE,
    RISK_GRANT,

    // ──────────────── 引擎 · handler ────────────────
    MISSING_PARAM_SQL,
    MISSING_PARAM_OUTPUT_DIR,
    MISSING_PARAM_FILE_NAME,
    MISSING_PARAM_FORMAT,
    UNSUPPORTED_FORMAT,
    EXPORT_HUB_NOT_READY,
    EXPORT_NO_COMPLETION_FRAME,
    MISSING_PARAM_TABLE_NAME,
    MISSING_PARAM_FK_NAME,
    MISSING_PARAM_COLUMNS,
    MISSING_PARAM_REF_TABLE,
    MISSING_PARAM_REF_COLUMNS,
    MISSING_PARAM_INDEX_NAME,
    MISSING_PARAM_NAME,
    MISSING_PARAM_DDL,
    ROUTINE_CREATED,
    MISSING_PARAM_ROUTINE_TYPE,
    ROUTINE_DROPPED,
    DDL_VALIDATED,
    MISSING_PARAM_DEFINITION,
    SCHEMA_LIST_REQUIRES_DATABASE,
    UNSUPPORTED_SCHEMA_LEVEL,

    // ──────────────── 引擎 · 导入 ────────────────
    CSV_NO_HEADER_NEEDS_COLUMNS,
    CSV_READER_SINGLE_USE,
    CSV_READER_EOF,
    UNSUPPORTED_IMPORT_FORMAT,
    IMPORT_PATH_EMPTY,
    IMPORT_FILE_NOT_FOUND,
    IMPORT_FILE_UNREADABLE,
    IMPORT_ENCODING_INVALID,
    IMPORT_ENCODING_UNSUPPORTED,
    IMPORT_FILE_OPEN_FAILED,
    JSONL_READER_SINGLE_USE,
    JSONL_READER_EOF,
    JSONL_INVALID_JSON,
    JSONL_NOT_OBJECT,
    JSON_TYPE_STRING,
    JSON_TYPE_SCALAR,
    ARRAY,

    // ──────────────── 引擎 · 导出 ────────────────
    EXPORT_SQL_INSERT_NEEDS_TABLE,
    EXPORT_SUBPROCESS_EXITED,
    EXPORT_SUBPROCESS_NOT_READY,
    EXPORT_CHANNEL_BROKEN,
    EXPORT_CHANNEL_CLOSED,

    // ──────────────── 方言不支持 ────────────────
    DIALECT_NO_VIEW_LIST,
    DIALECT_NO_CREATE_VIEW,
    DIALECT_NO_DROP_VIEW,
    DIALECT_NO_VIEW_DDL,
    DIALECT_NO_INDEX_LIST,
    DIALECT_NO_CREATE_INDEX,
    DIALECT_NO_DROP_INDEX,
    DIALECT_NO_FOREIGN_KEY_LIST,
    DIALECT_NO_ADD_FOREIGN_KEY,
    DIALECT_NO_DROP_FOREIGN_KEY,
    DIALECT_NO_TRIGGER_LIST,
    DIALECT_NO_TRIGGER_DDL,
    DIALECT_NO_RENAME_TABLE,
    DIALECT_NO_TRUNCATE,
    DIALECT_NO_EXPLAIN,

    // ──────────────── Excel 转换 ────────────────
    EXCEL_FILE_NOT_FOUND,

    // ──────────────── DuckDB 方言 ────────────────

    /**
     * 下面这组错误消息原本就是英文，但它们会经引擎 `Response.error` **原样上屏** ——
     * 德语界面里蹦英文与蹦中文是同一种缺陷，所以一并入表。
     */
    DUCKDB_NO_USER_MANAGEMENT,
    DUCKDB_NO_USER_LIST,
    DUCKDB_NO_PRIVILEGE_MANAGEMENT,
    DUCKDB_NO_PRIVILEGE_QUERY,
    DUCKDB_NO_PROCEDURE,
    DUCKDB_NO_PROCEDURE_CALL,
    DUCKDB_NO_PROCEDURE_KEYWORD,
    DUCKDB_NO_POSTGRESQL_BODY,
    DUCKDB_MODIFY_NO_PROPERTY,
    DUCKDB_TABLE_NOT_FOUND,
    DUCKDB_ROUTINE_NOT_FOUND,
    DUCKDB_VIEW_NOT_FOUND,
    DUCKDB_VIEW_NO_DEFINITION,
    DUCKDB_UNSUPPORTED_ROUTINE_TYPE,
    DUCKDB_FK_NOT_FOUND,
    DUCKDB_FK_AMBIGUOUS,
    DUCKDB_DDL_MULTI_STATEMENT,
    DUCKDB_DDL_UNPARSABLE,
    DUCKDB_VIEW_DEF_SEMICOLON,
    DUCKDB_EXPLAIN_SEMICOLON,

    /**
     * `debugRoutine` 的 INFO 段落与两条失败说明。这些原本是英文的 `Name: …` / `(not found: …)`，
     * 经引擎返回后由函数调试面板原样显示 —— 同样是界面文案，一并入表。
     */
    DUCKDB_DEBUG_NAME,
    DUCKDB_DEBUG_SCHEMA,
    DUCKDB_DEBUG_TYPE,
    DUCKDB_DEBUG_LANGUAGE,
    DUCKDB_DEBUG_RETURN_TYPE,
    DUCKDB_DEBUG_NOT_FOUND,
    DUCKDB_DEBUG_NO_DDL,

    /**
     * 标识符校验（`sanitizeIdentifier`）的六类对象名词。调用方传 **键** 而不是英文短语 ——
     * 否则报错里会夹一句英文，在德语界面上又变成一处「英文漏出」。
     */
    IDENT_KIND_SCHEMA,
    IDENT_KIND_TABLE,
    IDENT_KIND_VIEW,
    IDENT_KIND_INDEX,
    IDENT_KIND_FOREIGN_KEY,
    IDENT_KIND_ROUTINE,

    /**
     * 标识符校验的五条失败原因，句式统一为「{0} + 原因」，`{0}` 传上面那组名词键的译文。
     * 拆开而不是合成一条：原因本身要跟着语言变（德语名词有性、俄语格变化），
     * 拼在一条里等于把语序焊死。
     */
    IDENT_EMPTY,
    IDENT_HAS_NULL_BYTE,
    IDENT_HAS_SINGLE_QUOTE,
    IDENT_HAS_BACKSLASH,
    IDENT_HAS_SEMICOLON,

    // ──────────────── 原生文件选择 ────────────────
    PICK_EXPORT_FILE,
    PICK_OUTPUT_DIR,
    PICK_DATA_SOURCE_PATH,

    // ──────────────── 只读闸门 ────────────────
    ENGINE_READ_ONLY_REJECTED,
    DIALECT_READ_ONLY,

    // ──────────────── 数据分析模式 ────────────────
    NAV_ANALYSIS,
    ANALYSIS_SOURCE_HEAD,
    ANALYSIS_SOURCE_NOTE,
    ANALYSIS_SOURCE_FOOT,

    // 「添加数据源」—— 分析模式**自己**的数据源清单（存在工作区 DuckDB 里）。
    ANALYSIS_ADD_SOURCE,
    ANALYSIS_SOURCE_KIND_REMOTE,
    ANALYSIS_SOURCE_KIND_FILE,
    ANALYSIS_SOURCE_PATH,
    ANALYSIS_SOURCE_FILE_HINT,
    ANALYSIS_SOURCE_SELECT_ALL,
    ANALYSIS_SOURCE_SELECT_AT_LEAST_ONE,
    ANALYSIS_SOURCE_ADDED,
    ANALYSIS_NO_SOURCE,
    ANALYSIS_NO_SOURCE_HINT,
    ANALYSIS_LOCAL_WORKSPACE,
    ANALYSIS_WORKSPACE_EMPTY,
    ANALYSIS_WORKSPACE_EMPTY_HINT,
    ANALYSIS_EDITOR_HINT,
    ANALYSIS_EDITOR_TITLE,
    ANALYSIS_RUN,
    ANALYSIS_RESULT_ROWS,
    ANALYSIS_RESULT_TRUNCATED,
    ANALYSIS_RESULT_EMPTY,
    ANALYSIS_RESULT_FAILED,
    ANALYSIS_ROW_COUNT,
    ANALYSIS_ELAPSED,
    ANALYSIS_ENGINE,
    ANALYSIS_TREE_LOADING,
    ANALYSIS_TREE_ERROR,
    ANALYSIS_PULL,
    ANALYSIS_PULLING,
    ANALYSIS_PULL_CANCEL,
    ANALYSIS_PULL_DONE,
    ANALYSIS_PULL_CANCELLED,
    ANALYSIS_PULL_FAILED,
    ANALYSIS_PULL_ROWS,
    ANALYSIS_STAGING_TABLE,

    // 「删除数据源」—— 分析模式树右键菜单（判据见 `analysisDeleteSourceTarget`）。
    ANALYSIS_DELETE_SOURCE,
    ANALYSIS_DELETE_SOURCE_CONFIRM,
    ANALYSIS_DELETE_SOURCE_CONFIRM_HINT,
    ANALYSIS_DELETE_SOURCE_CONFIRM_BTN,
    ANALYSIS_SOURCE_DELETED,
    ANALYSIS_DELETE_SOURCE_FAILED,
    ANALYSIS_DELETE_SOURCE_REFUSED,

    // ──────────────── 报表设计 ────────────────
    // 一张报表 = 若干部件；每个部件 = 一条 SQL + 一种图表类型。
    REPORT_WORKBENCH,
    REPORT_DESIGN,
    REPORT_LIST_TITLE,
    REPORT_NEW,
    REPORT_DEFAULT_NAME,
    REPORT_EMPTY,
    REPORT_EMPTY_HINT,
    REPORT_RENAME,
    REPORT_DELETE,
    REPORT_DELETE_CONFIRM,
    REPORT_ADD_WIDGET,
    REPORT_WIDGET_NEW,
    REPORT_WIDGET_EDIT,
    REPORT_WIDGET_DELETE,
    REPORT_WIDGET_DELETE_CONFIRM,
    REPORT_WIDGET_FALLBACK_TITLE,
    REPORT_NO_WIDGET,
    REPORT_NO_WIDGET_HINT,
    REPORT_WIDGET_NO_DATA,
    REPORT_WIDGET_NO_DATA_HINT,
    REPORT_CHART_CANNOT_DRAW,
    REPORT_RESULT_FAILED,
    REPORT_TYPE_LABEL,
    REPORT_TYPE_PIE,
    REPORT_TYPE_BAR,
    REPORT_TYPE_TABLE,
    REPORT_TITLE_LABEL,
    REPORT_SQL_LABEL,
    REPORT_PREVIEW_SAMPLE,
    REPORT_SAMPLE_DATA,
    REPORT_PREVIEW_EMPTY,
    REPORT_PREVIEW_HINT,
    REPORT_PREVIEW_RUNNING,
    CHART_PROBLEM_NO_COLUMNS,
    CHART_PROBLEM_NEED_TWO_COLUMNS,
    CHART_PROBLEM_VALUE_NOT_NUMERIC,
    CHART_PROBLEM_NEGATIVE_VALUES,
    REPORT_PREVIEW_STALE,
    CHART_PROBLEM_NO_DATA,

    // ──────────────── 报表设计用的数据树 ────────────────
    // 报表设计页与部件编辑页共用同一棵树：看得见有哪些表、哪些字段，
    // 点一下就能把引用用到 SQL 里 —— 不用记、不用抄。
    REPORT_DATA_TREE_TITLE,
    REPORT_DATA_TREE_EMPTY,
    REPORT_DATA_TREE_EMPTY_HINT,
    REPORT_DATA_TREE_CANVAS_HINT,
    REPORT_DATA_TREE_EDITOR_HINT,

    ENGINE_ANALYSIS_LOCAL_ONLY,

    // ──────────────── 树面板（浏览与分析共用） ────────────────
    TREE_PANE_COLLAPSE,
    TREE_PANE_EXPAND,

    // ──────────────── 启动选择 ────────────────
    START_MODE_TITLE,
    START_MODE_CONNECTIONS_HINT,
    START_MODE_ANALYSIS_HINT,
    ;

    companion object {
        /**
         * 键名 → 键的映射 —— 供**按名字反查**用。
         *
         * ## 谁需要按名字反查
         *
         * 连接表单规格（`dialect-contract` 模块）里的字段带一个 `StrKey`，
         * 而它要跨进程送到前端 —— proto 传不了 Kotlin 枚举，只能传**名字**。
         * 前端收到名字后再反查回 `StrKey` 去查文案表。
         *
         * 认不出来的名字返回 `null`（**不猜**）：猜一个键等于给用户看一句
         * 与字段语义不符的话，而「标签是空的」比「标签是错的」更容易被发现。
         */
        private val BY_NAME: Map<String, StrKey> = entries.associateBy { it.name }

        /** 按名字反查键；认不出来返回 `null`。 */
        fun fromName(value: String): StrKey? = BY_NAME[value]
    }
}
