package com.pennywiseai.sync

import io.ktor.http.HttpStatusCode
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

import kotlinx.serialization.Serializable

fun interface OtpSender {
    fun send(channel: String, destination: String, code: String)
}

class CapturingOtpSender : OtpSender {
    var lastCode: String = ""
    var lastChannel: String = ""
    var lastDestination: String = ""

    override fun send(channel: String, destination: String, code: String) {
        lastChannel = channel
        lastDestination = destination
        lastCode = code
    }
}

@Serializable
data class AuthStatus(
    val channels: List<String>,
    val emailHint: String? = null,
    val phoneHint: String? = null
)

@Serializable
data class LoginChallenge(
    val challengeId: String,
    val channel: String,
    val destinationHint: String,
    val expiresIn: Int
)

@Serializable
data class LoginSession(
    val sessionToken: String,
    val expiresAt: String,
    val expiresIn: Int
)

class AuthService(
    dataDir: File,
    private val pairingToken: String,
    private val email: String? = null,
    private val phone: String? = null,
    private val otpSender: OtpSender,
    private val smsUsername: String? = null,
    private val smsApiKey: String? = null,
    private val smsUrl: String? = null
) {
    private val dbFile = File(dataDir, "auth.sqlite")
    private val random = SecureRandom()

    init {
        dataDir.mkdirs()
        connection().use { ensureSchema(it) }
    }

    fun status(): AuthStatus {
        val channels = channels()
        return AuthStatus(
            channels = channels,
            emailHint = if ("email" in channels) maskEmail(email.orEmpty()) else null,
            phoneHint = if ("sms" in channels) maskPhone(phone.orEmpty()) else null
        )
    }

    fun startLogin(token: String, channel: String?, ip: String): LoginChallenge {
        throttle("login:$ip", LOGIN_LIMIT, WINDOW_SECONDS)
        if (pairingToken.isBlank() || token.isBlank() || !constantEquals(pairingToken, token)) {
            throw DashboardException(HttpStatusCode.Unauthorized, "unauthorized")
        }
        val available = channels()
        if (available.isEmpty()) {
            throw DashboardException(HttpStatusCode.ServiceUnavailable, "2FA is not configured")
        }
        val chosen = when {
            !channel.isNullOrBlank() -> channel.trim().lowercase()
            available.size == 1 -> available.single()
            else -> throw DashboardException(HttpStatusCode.BadRequest, "choose email or sms")
        }
        if (chosen !in available) {
            throw DashboardException(HttpStatusCode.BadRequest, "channel unavailable")
        }
        val destination = if (chosen == "sms") phone.orEmpty() else email.orEmpty()
        val code = random.nextInt(1_000_000).toString().padStart(6, '0')
        val id = randomHex(16)
        val expires = Instant.now().epochSecond + OTP_TTL
        connection().use { conn ->
            ensureSchema(conn)
            conn.prepareStatement(
                "INSERT INTO challenges (id, code_hash, channel, expires_at, attempts, ip) VALUES (?, ?, ?, ?, 0, ?)"
            ).use { stmt ->
                stmt.setString(1, id)
                stmt.setString(2, codeHash(id, code))
                stmt.setString(3, chosen)
                stmt.setLong(4, expires)
                stmt.setString(5, ip)
                stmt.executeUpdate()
            }
        }
        try {
            otpSender.send(chosen, destination, code)
        } catch (e: Exception) {
            connection().use { conn ->
                conn.prepareStatement("DELETE FROM challenges WHERE id = ?").use { stmt ->
                    stmt.setString(1, id)
                    stmt.executeUpdate()
                }
            }
            throw DashboardException(HttpStatusCode.ServiceUnavailable, "could not send sign-in code")
        }
        val hint = if (chosen == "sms") maskPhone(destination) else maskEmail(destination)
        return LoginChallenge(challengeId = id, channel = chosen, destinationHint = hint, expiresIn = OTP_TTL)
    }

    fun verify(challengeId: String, code: String, ip: String): LoginSession {
        throttle("verify:$ip", VERIFY_LIMIT, WINDOW_SECONDS)
        val id = challengeId.trim()
        val trimmed = code.trim()
        if (id.isEmpty() || !trimmed.matches(Regex("^[0-9]{6}$"))) {
            throw DashboardException(HttpStatusCode.BadRequest, "invalid code")
        }
        val now = Instant.now().epochSecond
        data class Row(val hash: String, val attempts: Int, val expires: Long)
        val row = connection().use { conn ->
            ensureSchema(conn)
            conn.prepareStatement("SELECT code_hash, attempts, expires_at FROM challenges WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else Row(rs.getString("code_hash"), rs.getInt("attempts"), rs.getLong("expires_at"))
                }
            }
        } ?: throw DashboardException(HttpStatusCode.Unauthorized, "code expired")
        if (row.expires < now) {
            throw DashboardException(HttpStatusCode.Unauthorized, "code expired")
        }
        if (row.attempts >= MAX_ATTEMPTS) {
            throw DashboardException(HttpStatusCode.Unauthorized, "too many attempts")
        }
        connection().use { conn ->
            conn.prepareStatement("UPDATE challenges SET attempts = attempts + 1 WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
        }
        if (!constantEquals(row.hash, codeHash(id, trimmed))) {
            throw DashboardException(HttpStatusCode.Unauthorized, "invalid code")
        }
        val session = randomHex(32)
        val expires = now + SESSION_TTL
        connection().use { conn ->
            conn.prepareStatement("DELETE FROM challenges WHERE id = ?").use { stmt ->
                stmt.setString(1, id)
                stmt.executeUpdate()
            }
            conn.prepareStatement(
                "INSERT INTO sessions (token_hash, expires_at, ip, created_at) VALUES (?, ?, ?, ?)"
            ).use { stmt ->
                stmt.setString(1, sha256(session))
                stmt.setLong(2, expires)
                stmt.setString(3, ip)
                stmt.setLong(4, now)
                stmt.executeUpdate()
            }
        }
        return LoginSession(
            sessionToken = session,
            expiresAt = Instant.ofEpochSecond(expires).toString(),
            expiresIn = SESSION_TTL
        )
    }

    fun logout(sessionToken: String?) {
        if (sessionToken.isNullOrBlank()) return
        connection().use { conn ->
            ensureSchema(conn)
            conn.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { stmt ->
                stmt.setString(1, sha256(sessionToken))
                stmt.executeUpdate()
            }
        }
    }

    fun sessionValid(sessionToken: String?): Boolean {
        if (sessionToken.isNullOrBlank()) return false
        if (pairingToken.isNotBlank() && constantEquals(pairingToken, sessionToken)) return false
        val now = Instant.now().epochSecond
        connection().use { conn ->
            ensureSchema(conn)
            conn.prepareStatement("SELECT expires_at FROM sessions WHERE token_hash = ?").use { stmt ->
                stmt.setString(1, sha256(sessionToken))
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return false
                    val expires = rs.getLong("expires_at")
                    if (expires < now) {
                        conn.prepareStatement("DELETE FROM sessions WHERE token_hash = ?").use { del ->
                            del.setString(1, sha256(sessionToken))
                            del.executeUpdate()
                        }
                        return false
                    }
                    return true
                }
            }
        }
    }

    private fun channels(): List<String> {
        val out = mutableListOf<String>()
        if (!email.isNullOrBlank()) out += "email"
        if (!phone.isNullOrBlank() && smsReady()) out += "sms"
        return out
    }

    private fun smsReady(): Boolean {
        val user = smsUsername.orEmpty()
        val key = smsApiKey.orEmpty()
        return (user.isNotBlank() && key.isNotBlank()) || !smsUrl.isNullOrBlank()
    }

    private fun throttle(key: String, limit: Int, window: Int) {
        val now = Instant.now().epochSecond
        connection().use { conn ->
            ensureSchema(conn)
            val row = conn.prepareStatement("SELECT window_start, count FROM throttle WHERE k = ?").use { stmt ->
                stmt.setString(1, key)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) null
                    else rs.getLong("window_start") to rs.getInt("count")
                }
            }
            if (row == null || row.first + window < now) {
                conn.prepareStatement("INSERT OR REPLACE INTO throttle (k, window_start, count) VALUES (?, ?, 1)").use {
                    it.setString(1, key)
                    it.setLong(2, now)
                    it.executeUpdate()
                }
                return
            }
            val count = row.second + 1
            conn.prepareStatement("UPDATE throttle SET count = ? WHERE k = ?").use {
                it.setInt(1, count)
                it.setString(2, key)
                it.executeUpdate()
            }
            if (count > limit) {
                throw DashboardException(HttpStatusCode.TooManyRequests, "too many attempts")
            }
        }
    }

    private fun codeHash(challengeId: String, code: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pairingToken.toByteArray(), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal("$challengeId:$code".toByteArray()))
    }

    private fun sha256(value: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        return HexFormat.of().formatHex(digest.digest(value.toByteArray()))
    }

    private fun randomHex(bytes: Int): String {
        val raw = ByteArray(bytes)
        random.nextBytes(raw)
        return HexFormat.of().formatHex(raw)
    }

    private fun constantEquals(left: String, right: String): Boolean {
        val a = left.toByteArray()
        val b = right.toByteArray()
        return a.size == b.size && java.security.MessageDigest.isEqual(a, b)
    }

    private fun connection(): Connection {
        return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
    }

    private fun ensureSchema(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS challenges (
                    id TEXT PRIMARY KEY,
                    code_hash TEXT NOT NULL,
                    channel TEXT NOT NULL,
                    expires_at INTEGER NOT NULL,
                    attempts INTEGER NOT NULL DEFAULT 0,
                    ip TEXT NOT NULL DEFAULT ''
                )
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    token_hash TEXT PRIMARY KEY,
                    expires_at INTEGER NOT NULL,
                    ip TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS throttle (
                    k TEXT PRIMARY KEY,
                    window_start INTEGER NOT NULL,
                    count INTEGER NOT NULL
                )
                """.trimIndent()
            )
        }
    }

    companion object {
        const val OTP_TTL = 600
        const val SESSION_TTL = 43_200
        const val MAX_ATTEMPTS = 5
        const val WINDOW_SECONDS = 900
        const val LOGIN_LIMIT = 5
        const val VERIFY_LIMIT = 10
    }
}

fun maskEmail(email: String): String {
    val at = email.indexOf('@')
    if (at <= 0) return "***"
    val user = email.substring(0, at)
    val domain = email.substring(at + 1)
    val userMask = user.take(1) + "*".repeat(maxOf(1, user.length - 1))
    val dot = domain.indexOf('.')
    if (dot <= 0) return "$userMask@***"
    val name = domain.substring(0, dot)
    val rest = domain.substring(dot)
    return "$userMask@${name.take(1)}***$rest"
}

fun maskPhone(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    if (digits.length < 4) return "***"
    return "+" + "*".repeat(maxOf(0, digits.length - 4)) + digits.takeLast(4)
}

fun defaultOtpSender(
    dataDir: File,
    smsUsername: String?,
    smsApiKey: String?,
    smsUrl: String?,
    mailFrom: String
): OtpSender = OtpSender { channel, destination, code ->
    val body = "Your PennyKE sign-in code is $code. It expires in 10 minutes. If you did not try to sign in, ignore this."
    if (System.getenv("PENNYKE_2FA_DEV") == "1") {
        File(dataDir, "LAST_OTP.txt").writeText(code)
        return@OtpSender
    }
    if (channel == "email") {
        sendUnixMail(destination, mailFrom, body)
        return@OtpSender
    }
    sendSms(destination, body, smsUsername, smsApiKey, smsUrl)
}

private fun sendUnixMail(to: String, from: String, body: String) {
    val sendmail = File("/usr/sbin/sendmail")
    if (!sendmail.isFile) {
        throw IllegalStateException("sendmail missing")
    }
    val proc = ProcessBuilder(sendmail.absolutePath, "-i", "-f", from, to).start()
    proc.outputWriter().use { writer ->
        writer.append("Subject: PennyKE sign-in code\n\n")
        writer.append(body)
        writer.append('\n')
    }
    if (proc.waitFor() != 0) {
        throw IllegalStateException("sendmail failed")
    }
}

private fun sendSms(
    phone: String,
    message: String,
    username: String?,
    apiKey: String?,
    hookUrl: String?
) {
    if (!hookUrl.isNullOrBlank()) {
        val conn = URI(hookUrl).toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        val payload = """{"to":${jsonStr(phone)},"message":${jsonStr(message)}}"""
        conn.outputStream.use { it.write(payload.toByteArray()) }
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("sms webhook failed")
        }
        return
    }
    if (username.isNullOrBlank() || apiKey.isNullOrBlank()) {
        throw IllegalStateException("sms not configured")
    }
    val conn = URI("https://api.africastalking.com/version1/messaging").toURL().openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("apiKey", apiKey)
    conn.setRequestProperty("Accept", "application/json")
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    val form = "username=${urlEnc(username)}&to=${urlEnc(phone)}&message=${urlEnc(message)}"
    conn.outputStream.use { it.write(form.toByteArray()) }
    if (conn.responseCode !in 200..299) {
        throw IllegalStateException("sms send failed")
    }
}

private fun jsonStr(value: String): String = buildString {
    append('"')
    value.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            else -> append(ch)
        }
    }
    append('"')
}

private fun urlEnc(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8)
