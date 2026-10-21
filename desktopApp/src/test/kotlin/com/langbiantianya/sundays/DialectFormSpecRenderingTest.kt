/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Copyright (c) 2026 langbiantianya <langbiantianya@outlook.com>
 */

package com.langbiantianya.sundays

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.langbiantianya.dialect.H2Dialect
import com.langbiantianya.dialect.MySQLDialect
import com.langbiantianya.dialect.contract.AuthMethod
import com.langbiantianya.engine.DialectInfoMapper
import com.langbiantianya.engine.IdbEngine
import com.langbiantianya.i18n.StrKey
import com.langbiantianya.loader.DialectLoader
import com.langbiantianya.sundays.connection.ConnectionManagerScreen
import com.langbiantianya.sundays.connection.DialectFormRegistry
import com.langbiantianya.sundays.connection.DialectType
import com.langbiantianya.sundays.connection.WizardStep
import com.langbiantianya.sundays.connection.withDialect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * **连接表单按 spec 渲染**的验收 —— 设计文档 §5.7 的那条判据：
 *
 * > 新增一个测试用假方言（`FakeDialect`）声明 3 个自定义字段 + 2 种认证方式，
 * > 断言：连接管理器**自动**渲染出这 3 个字段与认证下拉，**前端零改动**。
 *
 * 「前端零改动」怎么证明？靠**反证**：那三个字段的标签在前端代码里
 * **一个都没有**（只有 `FakeDialect` 声明了它们），所以它们能出现在界面上，
 * 就只能是 spec 渲染出来的。
 *
 * 而「MySQL 的内置字段**不出现**」这条更狠：同一个方言，换一份 spec，
 * 界面就换了一套字段 —— 这说明前端**没有**按方言写死的字段清单。
 */
@OptIn(ExperimentalTestApi::class)
class DialectFormSpecRenderingTest {
    private lateinit var engine: IdbEngine

    @Before
    fun setUp() {
        DialectLoader.registerForTesting("H2", H2Dialect())
        DialectLoader.registerForTesting("Mysql", MySQLDialect())
        engine = IdbEngine(driversDir = File("/nonexistent"), dialectsDir = File("/nonexistent"))
    }

    @After
    fun tearDown() {
        engine.close()
    }

    private fun newSession() = ConnectionSession(engine, CoroutineScope(Dispatchers.Default))

    @Composable
    private fun Screen(
        session: ConnectionSession,
        dialectForms: DialectFormRegistry,
    ) {
        val wizard = session.wizard
        ConnectionManagerScreen(
            connections = session.connectionList.connections,
            selectedConnection = session.selectedConnection,
            editingConnection = wizard.editingConnection,
            wizardStep = wizard.step,
            wizardFlow = wizard.flow,
            connectionStatuses = session.statuses,
            onSelectConnection = session::select,
            onNewConnection = session::newConnection,
            onQuickConnect = session::quickConnect,
            onEditConnection = session::edit,
            onSaveConnection = session::save,
            onQuickConnectDirect = session::quickConnectDirect,
            onDeleteConnection = session::delete,
            onCancelEdit = session::cancelEdit,
            onWizardNext = session::goToStep,
            onWizardBack = session::back,
            onUpdateEditingConnection = session::updateEditing,
            onTestConnection = session::testConnection,
            onConnect = session::connect,
            onDisconnect = session::disconnect,
            dialectForms = dialectForms,
            modifier = Modifier.fillMaxSize(),
        )
    }

    // =========================================================================
    // 假方言的规格确实带了过去
    // =========================================================================

    @Test
    fun `假方言的规格经 proto 往返后仍是三个自定义字段加两种认证方式`() {
        val fake = FakeDialect(H2Dialect())

        val info = DialectInfoMapper.toDialectInfo(fake)
        assertTrue(info.hasConnectionForm(), "假方言声明了规格，proto 里必须带上")

        val form = info.connectionForm.formsList.single()
        assertEquals("CLIENT_SERVER", form.connectionType)
        assertEquals(listOf("serviceName", "principal", "keytabPath"), form.fieldsList.map { it.id })
        assertEquals(listOf("PASSWORD", "KERBEROS"), info.connectionForm.authMethodsList)

        // ⚠️ 主体 / keytab 只在 Kerberos 下出现 —— 这条「字段挂在哪种认证方式下」
        // 也要能过 proto 往返，否则前端会显示一堆没意义的空框。
        assertEquals(listOf("KERBEROS"), form.fieldsList[1].authMethodsList)
        assertEquals(listOf("KERBEROS"), form.fieldsList[2].authMethodsList)
        assertTrue(form.fieldsList[0].authMethodsList.isEmpty(), "服务名在所有认证方式下都出现")
    }

    // =========================================================================
    // 界面：按 spec 渲染
    // =========================================================================

    @Test
    fun `凭据页按 spec 渲染出自定义字段 - 且不渲染该方言的内置字段`() =
        runComposeUiTest {
            val session = newSession()
            val fake = FakeDialect(H2Dialect())
            val registry = DialectFormRegistry(mapOf(DialectType.MYSQL to fake.connectionForm))

            setContent { ProvideTestStrings { MaterialTheme { Screen(session, registry) } } }

            session.newConnection()
            session.goToStep(WizardStep.CREDENTIALS)
            waitForIdle()

            // 假方言声明的字段出现了 —— 它们的标签在前端代码里**一个都没有**
            onNodeWithText(zh(StrKey.FIELD_SERVICE_NAME)).assertExists()
            onNodeWithText(zh(StrKey.FIELD_AUTH_METHOD)).assertExists()

            // ⚠️ MySQL 的内置字段**不出现** —— 同一个方言，换一份 spec 就换了一套字段，
            // 这说明前端**没有**按方言写死的字段清单。
            onNodeWithText(zh(StrKey.HOST)).assertDoesNotExist()
            onNodeWithText(zh(StrKey.PORT)).assertDoesNotExist()
            onNodeWithText(zh(StrKey.DATABASE_NAME)).assertDoesNotExist()
        }

    @Test
    fun `切到 Kerberos 认证后 - 只在 Kerberos 下出现的字段才出现`() =
        runComposeUiTest {
            val session = newSession()
            val fake = FakeDialect(H2Dialect())
            val registry = DialectFormRegistry(mapOf(DialectType.MYSQL to fake.connectionForm))

            setContent { ProvideTestStrings { MaterialTheme { Screen(session, registry) } } }

            session.newConnection()
            session.goToStep(WizardStep.CREDENTIALS)
            waitForIdle()

            // 默认是账密 —— Kerberos 专属的字段不该出现
            onNodeWithText(zh(StrKey.FIELD_KERBEROS_PRINCIPAL)).assertDoesNotExist()
            onNodeWithText(zh(StrKey.FIELD_KEYTAB_PATH)).assertDoesNotExist()

            // 打开认证方式下拉 → 选 Kerberos
            onNodeWithText(zh(StrKey.AUTH_METHOD_PASSWORD)).performClick()
            waitForIdle()
            onNodeWithText(zh(StrKey.AUTH_METHOD_KERBEROS)).performClick()
            waitForIdle()

            onNodeWithText(zh(StrKey.FIELD_KERBEROS_PRINCIPAL)).assertExists()
            onNodeWithText(zh(StrKey.FIELD_KEYTAB_PATH)).assertExists()
            // 所有认证方式下都出现的那个一直在
            onNodeWithText(zh(StrKey.FIELD_SERVICE_NAME)).assertExists()
        }

    @Test
    fun `只有一种认证方式时不渲染下拉`() =
        runComposeUiTest {
            val session = newSession()
            // MySQL 自己的规格：只支持账密 → 没有下拉（内置 5 个方言都如此，
            // 所以接上引擎之后界面上**看不到**这个下拉，行为零变化）
            val registry = DialectFormRegistry(mapOf(DialectType.MYSQL to MySQLDialect().connectionForm))

            setContent { ProvideTestStrings { MaterialTheme { Screen(session, registry) } } }

            session.newConnection()
            session.goToStep(WizardStep.CREDENTIALS)
            waitForIdle()

            onNodeWithText(zh(StrKey.FIELD_AUTH_METHOD)).assertDoesNotExist()
            // 但 MySQL 的六格都在
            onNodeWithText(zh(StrKey.HOST)).assertExists()
            onNodeWithText(zh(StrKey.PORT)).assertExists()
        }

    // =========================================================================
    // 回退：没有规格时走旧的枚举分支
    // =========================================================================

    @Test
    fun `没有规格时回退旧的枚举分支 - 内置字段照常出现`() =
        runComposeUiTest {
            val session = newSession()

            // 空注册表 = 所有方言都走旧分支
            setContent { ProvideTestStrings { MaterialTheme { Screen(session, DialectFormRegistry.EMPTY) } } }

            session.newConnection()
            session.goToStep(WizardStep.CREDENTIALS)
            waitForIdle()

            onNodeWithText(zh(StrKey.HOST)).assertExists()
            onNodeWithText(zh(StrKey.PORT)).assertExists()
            onNodeWithText(zh(StrKey.DATABASE_NAME)).assertExists()
            onNodeWithText(zh(StrKey.USERNAME)).assertExists()
            onNodeWithText(zh(StrKey.PASSWORD)).assertExists()
        }

    // =========================================================================
    // 端到端：引擎 → session → 界面
    // =========================================================================

    @Test
    fun `端到端 - 引擎下发的规格经 session 进到界面`() =
        runComposeUiTest {
            val session = newSession()

            // 应用启动时拉一次（main.kt 的 LaunchedEffect 就是这么做的）。
            // 先拉再 setContent：注册表是**一次性**的元数据查询，不是每次渲染都拉。
            runBlocking { session.refreshDialectForms() }

            setContent { ProvideTestStrings { MaterialTheme { Screen(session, session.dialectForms) } } }

            val registry = session.dialectForms
            assertTrue(!registry.isEmpty, "引擎应当下发了至少一个方言的表单规格")
            assertTrue(registry.of(DialectType.MYSQL) != null, "MySQL 的规格应当到位")

            session.newConnection()
            session.goToStep(WizardStep.CREDENTIALS)
            waitForIdle()

            // spec 分支渲染出来的六格 —— 与旧分支的六格**逐格一致**
            onNodeWithText(zh(StrKey.HOST)).assertExists()
            onNodeWithText(zh(StrKey.PORT)).assertExists()
            onNodeWithText(zh(StrKey.DATABASE_NAME)).assertExists()
            onNodeWithText(zh(StrKey.USERNAME)).assertExists()
            onNodeWithText(zh(StrKey.PASSWORD)).assertExists()
            onNodeWithText(zh(StrKey.FIELD_JDBC_URL)).assertExists()
        }

    @Test
    fun `认证方式只有一个时 - 契约里也只声明一个`() {
        // 这条是「行为零变化」的另一半：内置 5 个方言都只支持账密，
        // 所以界面上不会出现认证方式下拉（与 M1 之前一模一样）。
        assertEquals(listOf(AuthMethod.PASSWORD), MySQLDialect().connectionForm.authMethods)
        assertEquals(listOf(AuthMethod.PASSWORD), H2Dialect().connectionForm.authMethods)
    }

    // =========================================================================
    // 嵌入式方言：spec 分支必须与旧分支一样**只有一个可编辑框**
    // =========================================================================

    @Test
    fun `嵌入式方言的 spec 分支是「一个目标字段加只读 URL」`() =
        runComposeUiTest {
            val session = newSession()
            val registry = DialectFormRegistry(mapOf(DialectType.H2 to H2Dialect().connectionForm))

            setContent { ProvideTestStrings { MaterialTheme { Screen(session, registry) } } }

            session.newConnection()
            // 草稿的默认方言是 MySQL —— 要先切到 H2，否则命中的是 MySQL 那份规格
            val draft = session.wizard.editingConnection!!
            session.updateEditing(draft.withDialect(DialectType.H2))
            session.goToStep(WizardStep.CREDENTIALS)
            waitForIdle()

            // 目标字段（内存库 = 库名）
            onNodeWithText(zh(StrKey.DATABASE_NAME)).assertExists()
            // 折算出来的 URL 是**只读展示**
            onNodeWithText(zh(StrKey.JDBC_URL_DERIVED)).assertExists()
            // ⚠️ 只有一个**可编辑**节点 —— 旧分支也是这个性质，
            // 而 `ConnectionManagerFlowTest` 正是靠「唯一的可编辑节点」打字的。
            // spec 分支若多出一个输入框（把 URL 变成可编辑），那条测试的写法就失配了。
            assertEquals(1, onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        }

    @Test
    fun `客户端服务端的 spec 分支里 URL 仍然是折算出来的`() =
        runComposeUiTest {
            val session = newSession()
            val registry = DialectFormRegistry(mapOf(DialectType.MYSQL to MySQLDialect().connectionForm))

            setContent { ProvideTestStrings { MaterialTheme { Screen(session, registry) } } }

            session.newConnection()
            session.goToStep(WizardStep.CREDENTIALS)
            waitForIdle()

            // 草稿的默认主机是 localhost、端口 3306 —— URL 输入框里显示的是折算结果
            onNodeWithText("jdbc:mysql://localhost:3306", substring = true).assertExists()
        }

    @Test
    fun `spec 分支里改字段会重建 URL`() =
        runComposeUiTest {
            val session = newSession()
            val registry = DialectFormRegistry(mapOf(DialectType.MYSQL to MySQLDialect().connectionForm))

            setContent { ProvideTestStrings { MaterialTheme { Screen(session, registry) } } }

            session.newConnection()
            session.goToStep(WizardStep.CREDENTIALS)
            waitForIdle()

            // 在「数据库名」里打字 → URL 跟着重算（spec 分支不是静态画出来的）
            onNode(hasSetTextAction() and hasText(zh(StrKey.DATABASE_NAME))).performTextInput("shop")
            waitForIdle()

            onNodeWithText("jdbc:mysql://localhost:3306/shop", substring = true).assertExists()
            assertEquals("shop", session.wizard.editingConnection?.database)
        }
}
