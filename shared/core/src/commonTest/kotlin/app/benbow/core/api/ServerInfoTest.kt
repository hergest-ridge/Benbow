package app.benbow.core.api

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerInfoTest {
    @Test
    fun usesSnakeCaseWireNames() {
        val json = Json.encodeToString(ServerInfo.serializer(), ServerInfo("0.1.0", 1, 2))
        assertEquals("""{"server_version":"0.1.0","protocol_min":1,"protocol_max":2}""", json)
    }

    @Test
    fun roundTrips() = runTest {
        checkAll(Arb.string(), Arb.int(), Arb.int()) { version, min, max ->
            val info = ServerInfo(version, min, max)
            assertEquals(info, Json.decodeFromString(ServerInfo.serializer(), Json.encodeToString(ServerInfo.serializer(), info)))
        }
    }
}
