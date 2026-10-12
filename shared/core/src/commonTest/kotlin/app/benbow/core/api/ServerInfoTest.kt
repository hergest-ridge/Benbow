package app.benbow.core.api

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerInfoTest {
    @Test
    fun usesSnakeCaseWireNames() {
        val json = Json.encodeToString(ServerInfo.serializer(), ServerInfo("0.1.0", 1, 2, "s-1", 3, 1))
        assertEquals(
            """{"server_version":"0.1.0","protocol_min":1,"protocol_max":2,"store_id":"s-1","epoch":3,"schema_version":1}""",
            json,
        )
    }

    @Test
    fun roundTrips() = runTest {
        checkAll(Arb.string(), Arb.int(), Arb.long()) { text, number, epoch ->
            val info = ServerInfo(text, number, number, text, epoch, number)
            assertEquals(info, Json.decodeFromString(ServerInfo.serializer(), Json.encodeToString(ServerInfo.serializer(), info)))
        }
    }
}
