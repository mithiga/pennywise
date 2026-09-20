package com.pennywiseai.sync

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class SyncServerTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun healthIsPublic() = testApplication {
        val dataDir = tempDir.resolve("health").toFile()
        application { syncModule(dataDir, tokenOverride = "secret-token") }
        val response = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun rejectsMissingToken() = testApplication {
        val dataDir = tempDir.resolve("auth").toFile()
        application { syncModule(dataDir, tokenOverride = "secret-token") }
        val response = client.post("/v1/sync") {
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-a", 0, sampleEntity("hash-1")))
        }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun twoDevicesExchangeTransactionsAndTombstones() = testApplication {
        val dataDir = tempDir.resolve("pair").toFile()
        application { syncModule(dataDir, tokenOverride = "secret-token") }

        val first = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-a", 0, sampleEntity("hash-1")))
        }
        assertEquals(HttpStatusCode.OK, first.status)
        val firstBody = json.parseToJsonElement(first.bodyAsText()).jsonObject
        assertEquals(1, firstBody["revision"]!!.jsonPrimitive.content.toLong())
        assertTrue(firstBody["upserts"]!!.jsonArray.isEmpty())

        val second = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-b", 0))
        }
        val secondBody = json.parseToJsonElement(second.bodyAsText()).jsonObject
        assertEquals(1, secondBody["revision"]!!.jsonPrimitive.content.toLong())
        assertEquals(1, secondBody["upserts"]!!.jsonArray.size)
        assertEquals("hash-1", secondBody["upserts"]!!.jsonArray[0].jsonObject["key"]!!.jsonPrimitive.content)

        val duplicate = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-a", 1, sampleEntity("hash-1")))
        }
        val duplicateBody = json.parseToJsonElement(duplicate.bodyAsText()).jsonObject
        assertEquals(1, duplicateBody["revision"]!!.jsonPrimitive.content.toLong())

        val delete = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    SyncRequest(
                        deviceId = "phone-a",
                        baseRevision = 1,
                        deletes = listOf(SyncTombstone(type = "transactions", key = "hash-1"))
                    )
                )
            )
        }
        val deleteBody = json.parseToJsonElement(delete.bodyAsText()).jsonObject
        assertEquals(2, deleteBody["revision"]!!.jsonPrimitive.content.toLong())

        val pullDelete = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-b", 1))
        }
        val pullBody = json.parseToJsonElement(pullDelete.bodyAsText()).jsonObject
        assertEquals(1, pullBody["deletes"]!!.jsonArray.size)
        assertEquals("hash-1", pullBody["deletes"]!!.jsonArray[0].jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun generateTokenFileWhenMissing() {
        val dataDir = File(tempDir.resolve("token").toFile(), "nested")
        val store = SyncStore(dataDir)
        val token = store.ensureToken()
        assertTrue(token.length >= 32)
        assertEquals(token, File(dataDir, "PAIRING.txt").readText().trim())
        assertEquals(token, store.ensureToken())
    }

    @Test
    fun laterEditReplacesSameHashAndStaleWriteIsIgnored() = testApplication {
        val dataDir = tempDir.resolve("edit").toFile()
        application { syncModule(dataDir, tokenOverride = "secret-token") }

        client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-a", 0, sampleEntity("hash-1", "100.00", "2026-09-20T10:00:00")))
        }

        val edited = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-a", 1, sampleEntity("hash-1", "250.00", "2026-09-20T11:00:00")))
        }
        val editedBody = json.parseToJsonElement(edited.bodyAsText()).jsonObject
        assertEquals(2, editedBody["revision"]!!.jsonPrimitive.content.toLong())

        val pull = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-b", 0))
        }
        val pullBody = json.parseToJsonElement(pull.bodyAsText()).jsonObject
        val upsert = pullBody["upserts"]!!.jsonArray.single().jsonObject
        assertEquals("hash-1", upsert["key"]!!.jsonPrimitive.content)
        assertEquals("250.00", upsert["payload"]!!.jsonObject["amount"]!!.jsonPrimitive.content)

        val stale = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-b", 2, sampleEntity("hash-1", "100.00", "2026-09-20T10:00:00")))
        }
        val staleBody = json.parseToJsonElement(stale.bodyAsText()).jsonObject
        assertEquals(2, staleBody["revision"]!!.jsonPrimitive.content.toLong())
    }

    private fun sampleEntity(
        hash: String,
        amount: String = "100.00",
        updatedAt: String = "2026-09-19T10:00:00"
    ) = SyncEntity(
        type = "transactions",
        key = hash,
        payload = buildJsonObject {
            put("transactionHash", hash)
            put("amount", amount)
            put("category", if (amount == "100.00") "Food" else "Shopping")
        },
        updatedAt = updatedAt
    )

    private fun sampleRequest(
        deviceId: String,
        baseRevision: Long,
        vararg upserts: SyncEntity
    ): String = json.encodeToString(
        SyncRequest(
            deviceId = deviceId,
            baseRevision = baseRevision,
            upserts = upserts.toList()
        )
    )
}
