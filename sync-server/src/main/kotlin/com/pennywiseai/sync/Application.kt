package com.pennywiseai.sync

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
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
    val dashboard = DashboardService(store)
    val spaDir = findSpaDir(dataDir)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }
    install(StatusPages) {
        exception<DashboardException> { call, cause ->
            call.respond(cause.status, ErrorBody(cause.message))
        }
        exception<Throwable> { call, cause ->
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorBody(cause.message ?: "internal error")
            )
        }
    }

    routing {
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
            if (!authorized(call.request.header("Authorization"), token)) {
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

        get("/v1/dashboard/summary") {
            if (!authorized(call.request.header("Authorization"), token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@get
            }
            call.respond(dashboard.summary())
        }
        get("/v1/dashboard/transactions") {
            if (!authorized(call.request.header("Authorization"), token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@get
            }
            val items = dashboard.listTransactions(
                query = call.request.queryParameters["q"],
                type = call.request.queryParameters["type"],
                account = call.request.queryParameters["account"],
                from = call.request.queryParameters["from"],
                to = call.request.queryParameters["to"]
            )
            call.respond(DashboardTransactionsResponse(items))
        }
        get("/v1/dashboard/transactions/{hash}") {
            if (!authorized(call.request.header("Authorization"), token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@get
            }
            val hash = call.parameters["hash"].orEmpty()
            call.respond(dashboard.getTransaction(hash))
        }
        put("/v1/dashboard/transactions/{hash}") {
            if (!authorized(call.request.header("Authorization"), token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@put
            }
            val hash = call.parameters["hash"].orEmpty()
            val body = call.receive<DashboardTransactionWrite>()
            call.respond(dashboard.updateTransaction(hash, body))
        }
        post("/v1/dashboard/transactions") {
            if (!authorized(call.request.header("Authorization"), token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@post
            }
            val body = call.receive<DashboardTransactionWrite>()
            call.respond(HttpStatusCode.Created, dashboard.createTransaction(body))
        }
        delete("/v1/dashboard/transactions/{hash}") {
            if (!authorized(call.request.header("Authorization"), token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@delete
            }
            val hash = call.parameters["hash"].orEmpty()
            val revision = dashboard.deleteTransaction(hash)
            call.respond(mapOf("revision" to revision.toString(), "hash" to hash))
        }
        get("/v1/dashboard/accounts") {
            if (!authorized(call.request.header("Authorization"), token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@get
            }
            call.respond(DashboardAccountsResponse(dashboard.accounts()))
        }
        get("/v1/dashboard/categories") {
            if (!authorized(call.request.header("Authorization"), token)) {
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "unauthorized"))
                return@get
            }
            call.respond(DashboardCategories(dashboard.categories()))
        }

        val spa = spaDir
        val index = spa?.let { File(it, "index.html") }?.takeIf { it.isFile }
        if (spa != null && index != null) {
            val assets = File(spa, "assets")
            if (assets.isDirectory) {
                staticFiles("/assets", assets)
            }
            get("/") {
                call.respondFile(index)
            }
            get("/{path...}") {
                val relative = call.parameters.getAll("path")?.joinToString("/").orEmpty()
                if (relative.startsWith("v1/") || relative == "PennyKE.apk" || relative.startsWith("assets/")) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "not found"))
                    return@get
                }
                val file = File(spa, relative)
                if (file.isFile) {
                    call.respondFile(file)
                } else {
                    call.respondFile(index)
                }
            }
        } else {
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
                        <p>${if (apk != null) """<a href="/PennyKE.apk">Download PennyKE.apk</a>""" else "Build the desktop dashboard with npm run build in dashboard/."}</p>
                        </body></html>
                    """.trimIndent()
                )
            }
        }
    }
}

private fun authorized(header: String?, token: String): Boolean {
    val auth = header?.removePrefix("Bearer")?.trim()
    return !auth.isNullOrEmpty() && auth == token
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

internal fun findSpaDir(dataDir: File): File? {
    val named = System.getenv("PENNYKE_SPA")
    val candidates = listOfNotNull(
        named?.let { File(it) },
        File("dashboard/dist"),
        File(dataDir.parentFile, "dashboard/dist"),
        File(dataDir.parentFile?.parentFile, "dashboard/dist")
    )
    return candidates.firstOrNull { it.isDirectory && File(it, "index.html").isFile }
}
