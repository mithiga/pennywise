package com.pennywiseai.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SyncRequest(
    @SerialName("device_id")
    val deviceId: String,
    @SerialName("base_revision")
    val baseRevision: Long = 0,
    @SerialName("upserts")
    val upserts: List<SyncEntity> = emptyList(),
    @SerialName("deletes")
    val deletes: List<SyncTombstone> = emptyList(),
    @SerialName("preference_patch")
    val preferencePatch: JsonObject? = null
)

@Serializable
data class SyncResponse(
    @SerialName("revision")
    val revision: Long,
    @SerialName("upserts")
    val upserts: List<SyncEntity> = emptyList(),
    @SerialName("deletes")
    val deletes: List<SyncTombstone> = emptyList(),
    @SerialName("preference_patch")
    val preferencePatch: JsonObject? = null
)

@Serializable
data class SyncEntity(
    @SerialName("type")
    val type: String,
    @SerialName("key")
    val key: String,
    @SerialName("payload")
    val payload: JsonObject,
    @SerialName("updated_at")
    val updatedAt: String? = null
)

@Serializable
data class SyncTombstone(
    @SerialName("type")
    val type: String,
    @SerialName("key")
    val key: String
)

object SyncEntityTypes {
    const val PREFERENCES = "preferences"
    const val PREFERENCES_KEY = "global"
    const val TRANSACTIONS = "transactions"
    const val CATEGORIES = "categories"
    const val CARDS = "cards"
    const val ACCOUNT_BALANCES = "account_balances"
    const val DASHBOARD_DEVICE = "pennyke-web"
}
