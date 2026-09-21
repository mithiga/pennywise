package com.pennywiseai.sync

import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
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

    @Test
    fun dashboardRequiresSessionNotPairingToken() = testApplication {
        val dataDir = tempDir.resolve("dash-auth").toFile()
        val otp = CapturingOtpSender()
        application {
            syncModule(
                dataDir,
                tokenOverride = "secret-token",
                otpSender = otp,
                twoFactorEmail = "otp@example.test"
            )
        }
        val missing = client.get("/v1/dashboard/summary")
        assertEquals(HttpStatusCode.Unauthorized, missing.status)

        val pairing = client.get("/v1/dashboard/summary") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
        }
        assertEquals(HttpStatusCode.Unauthorized, pairing.status)

        val session = signIn(client, otp)
        val ok = client.get("/v1/dashboard/summary") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }
        assertEquals(HttpStatusCode.OK, ok.status)
    }

    @Test
    fun dashboardReadsSnapshotAndUpdatesTransaction() = testApplication {
        val dataDir = tempDir.resolve("dash-crud").toFile()
        val otp = CapturingOtpSender()
        application {
            syncModule(
                dataDir,
                tokenOverride = "secret-token",
                otpSender = otp,
                twoFactorEmail = "otp@example.test"
            )
        }

        val month = java.time.YearMonth.now()
        val day = month.atDay(minOf(5, month.lengthOfMonth())).toString()
        client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(
                sampleRequest(
                    "phone-a",
                    0,
                    fullEntity("hash-kes", "500.00", "KES", "EXPENSE", "${day}T10:00:00"),
                    fullEntity("hash-inr", "100.00", "INR", "EXPENSE", "${day}T11:00:00"),
                    fullEntity("hash-inc", "200.00", "KES", "INCOME", "${day}T09:00:00")
                )
            )
        }

        val session = signIn(client, otp)

        val summary = client.get("/v1/dashboard/summary") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }
        assertEquals(HttpStatusCode.OK, summary.status)
        val summaryBody = json.parseToJsonElement(summary.bodyAsText()).jsonObject
        val expense = summaryBody["expense"]!!.jsonObject
        val income = summaryBody["income"]!!.jsonObject
        assertEquals("500.00", expense["KES"]!!.jsonPrimitive.content)
        assertEquals("100.00", expense["INR"]!!.jsonPrimitive.content)
        assertEquals("200.00", income["KES"]!!.jsonPrimitive.content)
        assertTrue(!expense.containsKey("total"))
        assertTrue(!income.containsKey("INR"))

        val list = client.get("/v1/dashboard/transactions") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }
        val listed = json.parseToJsonElement(list.bodyAsText()).jsonObject["transactions"]!!.jsonArray
        assertEquals(3, listed.size)
        val firstSms = listed.first { it.jsonObject["hash"]!!.jsonPrimitive.content == "hash-kes" }.jsonObject
        assertEquals("Confirmed. KES 500.00 paid to Sample hash-kes.", firstSms["smsBody"]!!.jsonPrimitive.content)
        assertEquals("MPESA", firstSms["smsSender"]!!.jsonPrimitive.content)

        val updated = client.put("/v1/dashboard/transactions/hash-kes") {
            header(HttpHeaders.Authorization, "Bearer $session")
            contentType(ContentType.Application.Json)
            setBody("""{"category":"Transport","merchantName":"Shell"}""")
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val updatedBody = json.parseToJsonElement(updated.bodyAsText()).jsonObject
        assertEquals("hash-kes", updatedBody["hash"]!!.jsonPrimitive.content)
        assertEquals("Transport", updatedBody["transaction"]!!.jsonObject["category"]!!.jsonPrimitive.content)
        assertTrue(updatedBody["revision"]!!.jsonPrimitive.content.toLong() >= 4)

        val got = client.get("/v1/dashboard/transactions/hash-kes") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }
        val gotBody = json.parseToJsonElement(got.bodyAsText()).jsonObject
        assertEquals("Shell", gotBody["merchantName"]!!.jsonPrimitive.content)
        assertEquals("Transport", gotBody["category"]!!.jsonPrimitive.content)

        val phonePull = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-b", 0))
        }
        val phoneUpserts = json.parseToJsonElement(phonePull.bodyAsText()).jsonObject["upserts"]!!.jsonArray
        val edited = phoneUpserts.first { it.jsonObject["key"]!!.jsonPrimitive.content == "hash-kes" }.jsonObject
        assertEquals("Shell", edited["payload"]!!.jsonObject["merchantName"]!!.jsonPrimitive.content)
        assertEquals("Transport", edited["payload"]!!.jsonObject["category"]!!.jsonPrimitive.content)

        val accounts = client.get("/v1/dashboard/accounts") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }
        val accountList = json.parseToJsonElement(accounts.bodyAsText()).jsonObject["accounts"]!!.jsonArray
        assertEquals(1, accountList.size)
        assertEquals("M-PESA", accountList[0].jsonObject["bankName"]!!.jsonPrimitive.content)
        assertEquals(3, accountList[0].jsonObject["transactionCount"]!!.jsonPrimitive.content.toInt())

        val created = client.post("/v1/dashboard/transactions") {
            header(HttpHeaders.Authorization, "Bearer $session")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"amount":"75.50","merchantName":"Java House","category":"Food & Dining",
                 "transactionType":"EXPENSE","dateTime":"2026-09-20T08:00:00","currency":"KES"}
                """.trimIndent()
            )
        }
        assertEquals(HttpStatusCode.Created, created.status)
        val createdHash = json.parseToJsonElement(created.bodyAsText()).jsonObject["hash"]!!.jsonPrimitive.content
        assertTrue(createdHash.isNotBlank())

        val deleted = client.delete("/v1/dashboard/transactions/$createdHash") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }
        assertEquals(HttpStatusCode.OK, deleted.status)

        val missing = client.get("/v1/dashboard/transactions/$createdHash") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }
        assertEquals(HttpStatusCode.NotFound, missing.status)
    }

    @Test
    fun latestLiveOmitsTombstones() {
        val store = SyncStore(tempDir.resolve("snapshot").toFile())
        store.applyLocal(
            SyncRequest(
                deviceId = "phone-a",
                upserts = listOf(fullEntity("hash-1", "10.00", "KES", "EXPENSE", "2026-09-20T10:00:00"))
            )
        )
        assertEquals(1, store.latestLive("transactions").size)
        store.applyLocal(
            SyncRequest(
                deviceId = "pennyke-web",
                deletes = listOf(SyncTombstone(type = "transactions", key = "hash-1"))
            )
        )
        assertTrue(store.latestLive("transactions").isEmpty())
        assertEquals(null, store.latestByKey("transactions", "hash-1"))
    }

    @Test
    fun servesSpaAndKeepsHealthWhenDistExists() = testApplication {
        val dataDir = tempDir.resolve("spa-data").toFile()
        val spa = File(dataDir.parentFile, "dashboard/dist")
        spa.mkdirs()
        File(spa, "index.html").writeText("<html>PennyKE desktop</html>")
        application { syncModule(dataDir, tokenOverride = "secret-token") }

        val home = client.get("/")
        assertEquals(HttpStatusCode.OK, home.status)
        assertTrue(home.bodyAsText().contains("PennyKE desktop"))

        val health = client.get("/v1/health")
        assertEquals(HttpStatusCode.OK, health.status)
        assertTrue(health.bodyAsText().contains("ok"))
    }

    @Test
    fun dashboardLoginIssuesSessionAfterOtp() = testApplication {
        val dataDir = tempDir.resolve("dash-2fa").toFile()
        val otp = CapturingOtpSender()
        application {
            syncModule(
                dataDir,
                tokenOverride = "secret-token",
                otpSender = otp,
                twoFactorEmail = "otp@example.test",
                twoFactorPhone = "+254711111111"
            )
        }
        val status = client.get("/v1/dashboard/auth")
        assertEquals(HttpStatusCode.OK, status.status)
        val statusBody = json.parseToJsonElement(status.bodyAsText()).jsonObject
        assertEquals("email", statusBody["channels"]!!.jsonArray[0].jsonPrimitive.content)
        assertTrue(statusBody["emailHint"]!!.jsonPrimitive.content.contains("*"))

        val bad = client.post("/v1/dashboard/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"wrong","channel":"email"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, bad.status)

        val login = client.post("/v1/dashboard/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"secret-token","channel":"email"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val challengeId = json.parseToJsonElement(login.bodyAsText()).jsonObject["challengeId"]!!.jsonPrimitive.content
        assertTrue(otp.lastCode.matches(Regex("^[0-9]{6}$")))

        val wrong = client.post("/v1/dashboard/login/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"challengeId":"$challengeId","code":"000000"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)

        val verify = client.post("/v1/dashboard/login/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"challengeId":"$challengeId","code":"${otp.lastCode}"}""")
        }
        assertEquals(HttpStatusCode.OK, verify.status)
        val session = json.parseToJsonElement(verify.bodyAsText()).jsonObject["sessionToken"]!!.jsonPrimitive.content
        assertTrue(session.length >= 32)

        val sync = client.post("/v1/sync") {
            header(HttpHeaders.Authorization, "Bearer secret-token")
            contentType(ContentType.Application.Json)
            setBody(sampleRequest("phone-a", 0))
        }
        assertEquals(HttpStatusCode.OK, sync.status)

        val summary = client.get("/v1/dashboard/summary") {
            header(HttpHeaders.Authorization, "Bearer $session")
        }
        assertEquals(HttpStatusCode.OK, summary.status)
    }

    @Test
    fun authServiceSendsSmsWhenConfigured() {
        val otp = CapturingOtpSender()
        val auth = AuthService(
            dataDir = tempDir.resolve("auth-sms").toFile(),
            pairingToken = "secret-token",
            email = "otp@example.test",
            phone = "+254711111111",
            otpSender = otp,
            smsUrl = "http://127.0.0.1:9/unused"
        )
        val status = auth.status()
        assertEquals(listOf("email", "sms"), status.channels)
        val challenge = auth.startLogin("secret-token", "sms", "127.0.0.1")
        assertEquals("sms", challenge.channel)
        assertEquals("sms", otp.lastChannel)
        assertTrue(otp.lastCode.matches(Regex("^[0-9]{6}$")))
    }

    private suspend fun signIn(client: HttpClient, otp: CapturingOtpSender): String {
        val login = client.post("/v1/dashboard/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"token":"secret-token","channel":"email"}""")
        }
        assertEquals(HttpStatusCode.OK, login.status)
        val challengeId = json.parseToJsonElement(login.bodyAsText()).jsonObject["challengeId"]!!.jsonPrimitive.content
        val verify = client.post("/v1/dashboard/login/verify") {
            contentType(ContentType.Application.Json)
            setBody("""{"challengeId":"$challengeId","code":"${otp.lastCode}"}""")
        }
        assertEquals(HttpStatusCode.OK, verify.status)
        return json.parseToJsonElement(verify.bodyAsText()).jsonObject["sessionToken"]!!.jsonPrimitive.content
    }

    private fun fullEntity(
        hash: String,
        amount: String,
        currency: String,
        type: String,
        dateTime: String
    ) = SyncEntity(
        type = "transactions",
        key = hash,
        payload = buildJsonObject {
            put("transactionHash", hash)
            put("amount", amount)
            put("currency", currency)
            put("merchantName", "Sample $hash")
            put("category", "Food")
            put("transactionType", type)
            put("dateTime", dateTime)
            put("bankName", "M-PESA")
            put("accountNumber", "1234")
            put("smsBody", "Confirmed. KES 500.00 paid to Sample $hash.")
            put("smsSender", "MPESA")
        },
        updatedAt = dateTime
    )

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
