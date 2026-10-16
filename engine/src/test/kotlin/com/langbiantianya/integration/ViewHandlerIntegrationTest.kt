package com.langbiantianya.integration

import com.langbiantianya.grpc.ViewCreateRequest
import com.langbiantianya.grpc.ViewDeleteRequest
import com.langbiantianya.grpc.ViewGetDdlRequest
import com.langbiantianya.grpc.ViewListRequest
import com.langbiantianya.handlers.ViewHandler
import com.langbiantianya.testutil.H2Fixture
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.langbiantianya.grpc.viewDeleteRequest
import com.langbiantianya.grpc.viewGetDdlRequest
import com.langbiantianya.grpc.viewListRequest

class ViewHandlerIntegrationTest : H2Fixture() {

    @Test
    fun `LIST returns empty initially`() = runBlocking {
        val result = ViewHandler.list(
            config,
            viewListRequest { schema = "PUBLIC" }
        )
        assertEquals(0, result.itemsCount)
    }

    @Test
    fun `CREATE then LIST then GET_DDL then DELETE view`() = runBlocking {
        executeUpdate("CREATE TABLE products (id INT, name VARCHAR(50))")

        ViewHandler.create(
            config,
            ViewCreateRequest.newBuilder()
                .setName("v_products")
                .setDefinition("SELECT id FROM products")
                .build()
        )

        val list = ViewHandler.list(
            config,
            viewListRequest { schema = "PUBLIC" }
        )
        assertTrue(list.itemsList.any { it.name.equals("v_products", ignoreCase = true) })

        val ddl = ViewHandler.getDDL(
            config,
            viewGetDdlRequest { name = "v_products"; schema = "PUBLIC" }
        )
        assertTrue(ddl.ddl.contains("CREATE VIEW", ignoreCase = true))

        ViewHandler.delete(
            config,
            viewDeleteRequest { name = "v_products"; ifExists = true }
        )
        val after = ViewHandler.list(
            config,
            viewListRequest { schema = "PUBLIC" }
        )
        assertFalse(after.itemsList.any { it.name.equals("v_products", ignoreCase = true) })
    }

    @Test
    fun `DELETE with ifExists does not throw on missing view`() = runBlocking {
        ViewHandler.delete(
            config,
            viewDeleteRequest { name = "no_such_view"; ifExists = true }
        )
        assertTrue(true)
    }
}