package com.kxxnzstdsw.integration

import com.kxxnzstdsw.grpc.ConnectionConfig
import com.kxxnzstdsw.handlers.SystemHandler
import com.kxxnzstdsw.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SystemHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `info returns JVM stats without database connection`() {
        val info = SystemHandler.info()
        assertNotNull(info.jvmVersion)
        assertTrue(info.jvmVersion.isNotEmpty())
        assertNotNull(info.jvmName)
        assertTrue(info.availableProcessors >= 1)
        assertTrue(info.memory.max > 0L, "max memory must be > 0")
        assertTrue(info.memory.total >= 0L)
        assertTrue(info.memory.used >= 0L)
        assertTrue(info.memory.free >= 0L)
        assertTrue(info.uptime > 0L)
        assertTrue(info.pid > 0L)
    }

    @Test
    fun `testConnection returns ok=true for valid H2 connection`() = runBlocking {
        val result = SystemHandler.testConnection(config)
        assertTrue(result.ok)
        assertEquals("H2", result.driver)
        assertEquals("", result.error, "no error on success")
    }

    @Test
    fun `testConnection works with JDBC-URL-only config`() = runBlocking {
        // 只给 jdbcUrl + 凭据 —— driver/host/port/database 全空，方言由 URL scheme 反查
        val urlOnly = ConnectionConfig.newBuilder()
            .setJdbcUrl(jdbcUrl)
            .setUser("sa")
            .build()

        val result = SystemHandler.testConnection(urlOnly)

        assertTrue(result.ok, "URL-only config should connect: ${result.error}")
        assertEquals("H2", result.driver, "driver is reported from the URL-resolved dialect")
    }

    @Test
    fun `testConnection fails with readable error when url matches no dialect`() = runBlocking {
        val bogus = ConnectionConfig.newBuilder()
            .setJdbcUrl("jdbc:oracle:thin:@localhost:1521/xe")
            .build()

        val result = SystemHandler.testConnection(bogus)

        assertFalse(result.ok)
        assertTrue(
            result.error.contains("No dialect plugin matches"),
            "expected dialect-resolution error, got: ${result.error}",
        )
    }

    @Test
    fun `serverInfo returns H2 product name and version`() = runBlocking {
        val result = SystemHandler.serverInfo(config)
        // H2 dialect returns: product, version, driver, url, ... (dialect-specific keys)
        // version is on the typed field; extras holds the rest
        assertNotNull(result.version)
        assertTrue(result.version.isNotEmpty())
        assertTrue(result.hasExtras(), "dialect-specific keys packed into extras")
        val extrasObj = result.extras
        assertEquals(com.google.protobuf.Value.KindCase.STRUCT_VALUE, extrasObj.kindCase)
        val struct = extrasObj.structValue.fieldsMap
        assertTrue(struct["product"]?.stringValue?.contains("H2") == true)
        assertTrue(struct["url"]?.stringValue?.startsWith("jdbc:h2") == true)
    }
}