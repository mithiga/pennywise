package com.pennywiseai.tracker.data.sync

import android.util.Log
import com.pennywiseai.tracker.data.backup.BackupExporter
import com.pennywiseai.tracker.data.backup.BackupImporter
import com.pennywiseai.tracker.data.backup.ExportPrivacy
import com.pennywiseai.tracker.data.preferences.UserPreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncCoordinator @Inject constructor(
    private val client: SyncClient,
    private val backupExporter: BackupExporter,
    private val backupImporter: BackupImporter,
    private val userPreferencesRepository: UserPreferencesRepository
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun sync(): Result<String> = mutex.withLock {
        val enabled = userPreferencesRepository.deviceSyncEnabled.first()
        val url = userPreferencesRepository.deviceSyncServerUrl.first()
        val token = userPreferencesRepository.deviceSyncToken.first()
        if (!enabled) return Result.failure(IllegalStateException("Device sync is off"))
        if (url.isBlank() || token.isBlank()) {
            return Result.failure(IllegalStateException("Set the sync server URL and pairing token"))
        }

        return try {
            val deviceId = userPreferencesRepository.ensureDeviceSyncId()
            val dirty = userPreferencesRepository.deviceSyncDirty.first()
            val prefsDirty = userPreferencesRepository.deviceSyncPreferencesDirty.first()
            val pendingDeletes = decodeDeletes(userPreferencesRepository.deviceSyncPendingDeletes.first())
            val snapshot = if (dirty || prefsDirty) {
                backupExporter.createBackupSnapshot(ExportPrivacy.FULL)
            } else {
                null
            }
            val request = SyncRequest(
                deviceId = deviceId,
                baseRevision = userPreferencesRepository.deviceSyncRevision.first(),
                upserts = if (dirty && snapshot != null) SyncMapper.toUpserts(snapshot.database) else emptyList(),
                deletes = pendingDeletes,
                preferencePatch = if (prefsDirty && snapshot != null) {
                    SyncMapper.encodePreferences(snapshot.preferences)
                } else {
                    null
                }
            )
            val response = client.sync(url, token, request)
            if (response.upserts.isNotEmpty() || response.deletes.isNotEmpty() || response.preferencePatch != null) {
                val inboundPrefs = response.preferencePatch?.let { SyncMapper.decodePreferences(it) }
                backupImporter.importBackupSnapshot(
                    backup = SyncMapper.toBackup(response.upserts, inboundPrefs ?: com.pennywiseai.tracker.data.backup.PreferencesSnapshot()),
                    applyPreferences = inboundPrefs != null,
                    updateExistingIfNewer = true,
                    syncSafePreferences = true
                )
                backupImporter.applyTombstones(response.deletes)
            }
            userPreferencesRepository.setDeviceSyncRevision(response.revision)
            if (dirty) userPreferencesRepository.setDeviceSyncDirty(false)
            if (prefsDirty) userPreferencesRepository.setDeviceSyncPreferencesDirty(false)
            if (pendingDeletes.isNotEmpty()) {
                userPreferencesRepository.setDeviceSyncPendingDeletes("[]")
            }
            val last = "Synced to revision ${response.revision}"
            userPreferencesRepository.setDeviceSyncLastStatus(last)
            Result.success(last)
        } catch (e: Exception) {
            Log.w(TAG, "Device sync failed", e)
            val message = e.message ?: "Sync failed"
            userPreferencesRepository.setDeviceSyncLastStatus(message)
            Result.failure(e)
        }
    }

    suspend fun enqueueDelete(type: String, key: String) {
        val current = decodeDeletes(userPreferencesRepository.deviceSyncPendingDeletes.first()).toMutableList()
        if (current.none { it.type == type && it.key == key }) {
            current += SyncTombstone(type = type, key = key)
            userPreferencesRepository.setDeviceSyncPendingDeletes(json.encodeToString(current))
        }
        userPreferencesRepository.setDeviceSyncDirty(true)
    }

    private fun decodeDeletes(raw: String): List<SyncTombstone> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<SyncTombstone>>(raw) }.getOrDefault(emptyList())
    }

    companion object {
        private const val TAG = "SyncCoordinator"
    }
}
