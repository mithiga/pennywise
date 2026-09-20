package com.pennywiseai.tracker.data.sync

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
    const val TRANSACTIONS = "transactions"
    const val CATEGORIES = "categories"
    const val CARDS = "cards"
    const val ACCOUNT_BALANCES = "account_balances"
    const val SUBSCRIPTIONS = "subscriptions"
    const val MERCHANT_MAPPINGS = "merchant_mappings"
    const val MERCHANT_ALIASES = "merchant_aliases"
    const val UNRECOGNIZED_SMS = "unrecognized_sms"
    const val CHAT_MESSAGES = "chat_messages"
    const val RULES = "rules"
    const val RULE_APPLICATIONS = "rule_applications"
    const val EXCHANGE_RATES = "exchange_rates"
    const val BUDGETS = "budgets"
    const val BUDGET_CATEGORIES = "budget_categories"
    const val TRANSACTION_SPLITS = "transaction_splits"
    const val BANK_NOTIFICATIONS = "bank_notifications"
    const val LOANS = "loans"
    const val TRANSACTION_GROUPS = "transaction_groups"
    const val PROFILES = "profiles"
    const val BUDGET_MONTH_SNAPSHOTS = "budget_month_snapshots"
    const val BUDGET_CATEGORY_MONTH_SNAPSHOTS = "budget_category_month_snapshots"
    const val TAGS = "tags"
    const val TRANSACTION_TAG_CROSS_REFS = "transaction_tag_cross_refs"
    const val RECURRING_TRANSACTIONS = "recurring_transactions"
    const val PREFERENCES = "preferences"
}
