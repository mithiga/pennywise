package com.pennywiseai.sync

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import java.io.File

fun main() {
    val port = System.getenv("SYNC_PORT")?.toIntOrNull() ?: 8080
    val dataDir = File(System.getenv("SYNC_DATA_DIR") ?: "data")
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        syncModule(dataDir)
    }.start(wait = true)
}

fun Application.syncModule(dataDir: File, tokenOverride: String? = null) {
    val store = SyncStore(dataDir)
    val token = tokenOverride ?: store.ensureToken()

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to (cause.message ?: "internal error"))
            )
        }
    }

    routing {
        get("/") {
            val apk = findPennyKeApk(dataDir)
            call.respondText(
                contentType = ContentType.Text.Html,
                text = """
                    <!DOCTYPE html>
                    <html><head><meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>PennyKE</title></head>
                    <body style="font-family:sans-serif;max-width:36rem;margin:2rem auto;padding:0 1rem">
                    <h1>PennyKE</h1>
                    <p>${if (apk != null) """<a href="/PennyKE.apk">Download PennyKE.apk</a>""" else "APK is not on this PC yet."}</p>
                    <p>Use this exact address in the phone browser, starting with <strong>http://</strong> not https://</p>
                    </body></html>
                """.trimIndent()
            )
        }
        get("/PennyKE.apk") {
            val apk = findPennyKeApk(dataDir)
            if (apk == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "apk not found"))
                return@get
            }
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"PennyKE.apk\"")
            call.respondFile(apk)
        }
        get("/v1/health") {
            call.respond(mapOf("status" to "ok"))
        }
        post("/v1/sync") {
            val auth = call.request.header("Authorization")
                ?.removePrefix("Bearer")
                ?.trim()
            if (auth.isNullOrEmpty() || auth != token) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
            val request = call.receive<SyncRequest>()
            if (request.deviceId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "device_id required"))
                return@post
            }
            call.respond(store.sync(request))
        }
    }
}

private fun findPennyKeApk(dataDir: File): File? {
    val named = System.getenv("PENNYKE_APK")
    val candidates = listOfNotNull(
        named?.let { File(it) },
        File("dist/PennyKE.apk"),
        File(dataDir.parentFile, "dist/PennyKE.apk"),
        File(dataDir.parentFile?.parentFile, "dist/PennyKE.apk")
    )
    return candidates.firstOrNull { it.isFile }
}
