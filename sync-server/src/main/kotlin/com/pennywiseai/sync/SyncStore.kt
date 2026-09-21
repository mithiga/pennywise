package com.pennywiseai.sync

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.HexFormat

class SyncStore(
    private val dataDir: File,
    private val json: Json = SyncJson
) {
    private val dbFile = File(dataDir, "sync.sqlite")
    private val pairingFile = File(dataDir, "PAIRING.txt")

    fun ensureToken(): String {
        dataDir.mkdirs()
        val env = System.getenv("SYNC_TOKEN")
        if (!env.isNullOrBlank()) {
            if (!pairingFile.exists()) {
                pairingFile.writeText(env.trim() + "\n")
            }
            return env.trim()
        }
        if (pairingFile.exists()) {
            val existing = pairingFile.readText().trim()
            if (existing.isNotEmpty()) return existing
        }
        val generated = generateToken()
        pairingFile.writeText(generated + "\n")
        println("Generated household pairing token. Saved to ${pairingFile.absolutePath}")
        println("Token: $generated")
        return generated
    }

    fun latestLive(type: String): List<SyncEntity> {
        dataDir.mkdirs()
        connection().use { conn ->
            ensureSchema(conn)
            return latestLive(conn, type)
        }
    }

    fun latestByKey(type: String, key: String): SyncEntity? {
        dataDir.mkdirs()
        connection().use { conn ->
            ensureSchema(conn)
            val row = latestRow(conn, type, key)
            if (row == null || row.deleted || row.payload == null) return null
            return SyncEntity(
                type = type,
                key = key,
                payload = json.decodeFromString(row.payload),
                updatedAt = row.updatedAt
            )
        }
    }

    fun applyLocal(request: SyncRequest): Long {
        dataDir.mkdirs()
        connection().use { conn ->
            conn.autoCommit = false
            try {
                ensureSchema(conn)
                applyUpserts(conn, request)
                applyDeletes(conn, request)
                val revision = currentRevision(conn)
                conn.commit()
                return revision
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun sync(request: SyncRequest): SyncResponse {
        dataDir.mkdirs()
        connection().use { conn ->
            conn.autoCommit = false
            try {
                ensureSchema(conn)
                applyUpserts(conn, request)
                applyDeletes(conn, request)
                request.preferencePatch?.let { patch ->
                    applyPreferencePatch(conn, request.deviceId, patch)
                }
                val revision = currentRevision(conn)
                val inbound = changesSince(conn, request.baseRevision, request.deviceId)
                conn.commit()
                return inbound.copy(revision = revision)
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    private fun applyUpserts(conn: Connection, request: SyncRequest) {
        for (entity in request.upserts) {
            val encoded = json.encodeToString(entity.payload)
            val latest = latestRow(conn, entity.type, entity.key)
            if (latest != null && !latest.deleted && latest.payload == encoded) {
                continue
            }
            if (latest != null && !latest.deleted && isStale(entity.updatedAt, latest.updatedAt)) {
                continue
            }
            insertChange(
                conn = conn,
                type = entity.type,
                key = entity.key,
                payload = encoded,
                deleted = false,
                updatedAt = entity.updatedAt,
                originDevice = request.deviceId
            )
        }
    }

    private fun isStale(incoming: String?, stored: String?): Boolean {
        if (incoming.isNullOrBlank() || stored.isNullOrBlank()) return false
        return runCatching {
            java.time.LocalDateTime.parse(incoming).isBefore(java.time.LocalDateTime.parse(stored))
        }.getOrDefault(false)
    }

    private fun applyDeletes(conn: Connection, request: SyncRequest) {
        for (tombstone in request.deletes) {
            val latest = latestRow(conn, tombstone.type, tombstone.key)
            if (latest?.deleted == true) continue
            insertChange(
                conn = conn,
                type = tombstone.type,
                key = tombstone.key,
                payload = null,
                deleted = true,
                updatedAt = null,
                originDevice = request.deviceId
            )
        }
    }

    private fun applyPreferencePatch(conn: Connection, deviceId: String, patch: JsonObject) {
        val encoded = json.encodeToString(patch)
        val latest = latestRow(conn, SyncEntityTypes.PREFERENCES, SyncEntityTypes.PREFERENCES_KEY)
        if (latest != null && !latest.deleted && latest.payload == encoded) return
        insertChange(
            conn = conn,
            type = SyncEntityTypes.PREFERENCES,
            key = SyncEntityTypes.PREFERENCES_KEY,
            payload = encoded,
            deleted = false,
            updatedAt = null,
            originDevice = deviceId
        )
    }

    private fun changesSince(conn: Connection, baseRevision: Long, deviceId: String): SyncResponse {
        val upserts = mutableListOf<SyncEntity>()
        val deletes = mutableListOf<SyncTombstone>()
        var preferencePatch: JsonObject? = null

        conn.prepareStatement(
            """
            SELECT c.entity_type, c.stable_key, c.payload, c.deleted, c.updated_at
            FROM changes c
            INNER JOIN (
                SELECT entity_type, stable_key, MAX(revision) AS max_rev
                FROM changes
                WHERE revision > ?
                GROUP BY entity_type, stable_key
            ) latest
              ON c.entity_type = latest.entity_type
             AND c.stable_key = latest.stable_key
             AND c.revision = latest.max_rev
            WHERE c.origin_device <> ?
            ORDER BY c.revision ASC
            """.trimIndent()
        ).use { stmt ->
            stmt.setLong(1, baseRevision)
            stmt.setString(2, deviceId)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val type = rs.getString("entity_type")
                    val key = rs.getString("stable_key")
                    val deleted = rs.getInt("deleted") == 1
                    val payload = rs.getString("payload")
                    val updatedAt = rs.getString("updated_at")
                    if (type == SyncEntityTypes.PREFERENCES) {
                        if (!deleted && payload != null) {
                            preferencePatch = json.decodeFromString<JsonObject>(payload)
                        }
                        continue
                    }
                    if (deleted) {
                        deletes += SyncTombstone(type = type, key = key)
                    } else if (payload != null) {
                        upserts += SyncEntity(
                            type = type,
                            key = key,
                            payload = json.decodeFromString<JsonObject>(payload),
                            updatedAt = updatedAt
                        )
                    }
                }
            }
        }

        return SyncResponse(
            revision = 0,
            upserts = upserts,
            deletes = deletes,
            preferencePatch = preferencePatch
        )
    }

    private fun latestLive(conn: Connection, type: String): List<SyncEntity> {
        val items = mutableListOf<SyncEntity>()
        conn.prepareStatement(
            """
            SELECT c.stable_key, c.payload, c.updated_at
            FROM changes c
            INNER JOIN (
                SELECT entity_type, stable_key, MAX(revision) AS max_rev
                FROM changes
                WHERE entity_type = ?
                GROUP BY entity_type, stable_key
            ) latest
              ON c.entity_type = latest.entity_type
             AND c.stable_key = latest.stable_key
             AND c.revision = latest.max_rev
            WHERE c.deleted = 0 AND c.payload IS NOT NULL
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, type)
            stmt.executeQuery().use { rs ->
                while (rs.next()) {
                    val payload = rs.getString("payload") ?: continue
                    items += SyncEntity(
                        type = type,
                        key = rs.getString("stable_key"),
                        payload = json.decodeFromString(payload),
                        updatedAt = rs.getString("updated_at")
                    )
                }
            }
        }
        return items
    }

    private fun currentRevision(conn: Connection): Long {
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COALESCE(MAX(revision), 0) AS rev FROM changes").use { rs ->
                rs.next()
                return rs.getLong("rev")
            }
        }
    }

    private fun latestRow(conn: Connection, type: String, key: String): StoredRow? {
        conn.prepareStatement(
            """
            SELECT payload, deleted, updated_at FROM changes
            WHERE entity_type = ? AND stable_key = ?
            ORDER BY revision DESC LIMIT 1
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, type)
            stmt.setString(2, key)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) return null
                return StoredRow(
                    payload = rs.getString("payload"),
                    deleted = rs.getInt("deleted") == 1,
                    updatedAt = rs.getString("updated_at")
                )
            }
        }
    }

    private fun insertChange(
        conn: Connection,
        type: String,
        key: String,
        payload: String?,
        deleted: Boolean,
        updatedAt: String?,
        originDevice: String
    ) {
        conn.prepareStatement(
            """
            INSERT INTO changes (entity_type, stable_key, payload, deleted, updated_at, origin_device)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { stmt ->
            stmt.setString(1, type)
            stmt.setString(2, key)
            stmt.setString(3, payload)
            stmt.setInt(4, if (deleted) 1 else 0)
            stmt.setString(5, updatedAt)
            stmt.setString(6, originDevice)
            stmt.executeUpdate()
        }
    }

    private fun ensureSchema(conn: Connection) {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS changes (
                    revision INTEGER PRIMARY KEY AUTOINCREMENT,
                    entity_type TEXT NOT NULL,
                    stable_key TEXT NOT NULL,
                    payload TEXT,
                    deleted INTEGER NOT NULL DEFAULT 0,
                    updated_at TEXT,
                    origin_device TEXT NOT NULL
                )
                """.trimIndent()
            )
            stmt.execute(
                "CREATE INDEX IF NOT EXISTS idx_changes_lookup ON changes(entity_type, stable_key, revision)"
            )
        }
    }

    private fun connection(): Connection {
        dataDir.mkdirs()
        return DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
    }

    private fun generateToken(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return HexFormat.of().formatHex(bytes)
    }

    private data class StoredRow(
        val payload: String?,
        val deleted: Boolean,
        val updatedAt: String? = null
    )

    companion object {
        val SyncJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
