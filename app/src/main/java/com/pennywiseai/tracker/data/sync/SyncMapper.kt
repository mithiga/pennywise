package com.pennywiseai.tracker.data.sync

import com.pennywiseai.tracker.data.backup.DatabaseSnapshot
import com.pennywiseai.tracker.data.backup.PennyWiseBackup
import com.pennywiseai.tracker.data.backup.PreferencesSnapshot
import com.pennywiseai.tracker.data.backup.backupJson
import com.pennywiseai.tracker.data.database.entity.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

object SyncMapper {

    fun toUpserts(snapshot: DatabaseSnapshot): List<SyncEntity> {
        val upserts = mutableListOf<SyncEntity>()
        snapshot.transactions.forEach {
            upserts += entity(
                SyncEntityTypes.TRANSACTIONS,
                it.transactionHash,
                it.copy(id = 0),
                it.updatedAt.toString()
            )
        }
        snapshot.categories.forEach {
            upserts += entity(SyncEntityTypes.CATEGORIES, it.name, it, it.updatedAt.toString())
        }
        snapshot.cards.forEach {
            upserts += entity(SyncEntityTypes.CARDS, "${it.bankName}||${it.cardLast4}", it, it.updatedAt.toString())
        }
        snapshot.accountBalances.forEach {
            upserts += entity(
                SyncEntityTypes.ACCOUNT_BALANCES,
                "${it.bankName}||${it.accountLast4}||${it.timestamp}",
                it
            )
        }
        snapshot.subscriptions.forEach {
            upserts += entity(
                SyncEntityTypes.SUBSCRIPTIONS,
                "${it.merchantName}||${it.amount}",
                it,
                it.updatedAt.toString()
            )
        }
        snapshot.merchantMappings.forEach {
            upserts += entity(SyncEntityTypes.MERCHANT_MAPPINGS, it.merchantName, it)
        }
        snapshot.merchantAliases.forEach {
            upserts += entity(SyncEntityTypes.MERCHANT_ALIASES, it.merchantName, it)
        }
        snapshot.unrecognizedSms.forEach {
            upserts += entity(
                SyncEntityTypes.UNRECOGNIZED_SMS,
                "${it.sender}||${it.smsBody}",
                it
            )
        }
        snapshot.chatMessages.forEach {
            upserts += entity(SyncEntityTypes.CHAT_MESSAGES, it.id, it)
        }
        snapshot.rules.forEach {
            upserts += entity(SyncEntityTypes.RULES, it.id, it)
        }
        snapshot.ruleApplications.forEach {
            upserts += entity(SyncEntityTypes.RULE_APPLICATIONS, it.id, it)
        }
        snapshot.exchangeRates.forEach {
            upserts += entity(
                SyncEntityTypes.EXCHANGE_RATES,
                "${it.fromCurrency}||${it.toCurrency}",
                it
            )
        }
        snapshot.budgets.forEach {
            upserts += entity(SyncEntityTypes.BUDGETS, it.id.toString(), it)
        }
        snapshot.budgetCategories.forEach {
            upserts += entity(
                SyncEntityTypes.BUDGET_CATEGORIES,
                "${it.budgetId}||${it.categoryName}",
                it
            )
        }
        snapshot.transactionSplits.forEach {
            upserts += entity(
                SyncEntityTypes.TRANSACTION_SPLITS,
                "${it.transactionId}|${it.category}|${it.amount}",
                it
            )
        }
        snapshot.bankNotifications.forEach {
            upserts += entity(SyncEntityTypes.BANK_NOTIFICATIONS, it.id.toString(), it)
        }
        snapshot.loans.forEach {
            upserts += entity(
                SyncEntityTypes.LOANS,
                "${it.personName}||${it.direction}||${it.createdAt}",
                it
            )
        }
        snapshot.transactionGroups.forEach {
            upserts += entity(
                SyncEntityTypes.TRANSACTION_GROUPS,
                "${it.name}||${it.createdAt}",
                it
            )
        }
        snapshot.profiles.forEach {
            upserts += entity(SyncEntityTypes.PROFILES, it.name, it)
        }
        snapshot.budgetMonthSnapshots.forEach {
            upserts += entity(
                SyncEntityTypes.BUDGET_MONTH_SNAPSHOTS,
                "${it.year}|${it.month}|${it.id}",
                it
            )
        }
        snapshot.budgetCategoryMonthSnapshots.forEach {
            upserts += entity(
                SyncEntityTypes.BUDGET_CATEGORY_MONTH_SNAPSHOTS,
                "${it.year}|${it.month}|${it.id}",
                it
            )
        }
        snapshot.tags.forEach {
            upserts += entity(SyncEntityTypes.TAGS, it.name.lowercase(), it)
        }
        snapshot.transactionTagCrossRefs.forEach {
            upserts += entity(
                SyncEntityTypes.TRANSACTION_TAG_CROSS_REFS,
                "${it.transactionId}|${it.tagId}",
                it
            )
        }
        snapshot.recurringTransactions.forEach {
            upserts += entity(SyncEntityTypes.RECURRING_TRANSACTIONS, it.id.toString(), it)
        }
        return upserts
    }

    fun toBackup(
        upserts: List<SyncEntity>,
        preferences: PreferencesSnapshot = PreferencesSnapshot()
    ): PennyWiseBackup {
        return PennyWiseBackup(
            database = toSnapshot(upserts),
            preferences = preferences
        )
    }

    fun toSnapshot(upserts: List<SyncEntity>): DatabaseSnapshot {
        fun <T> decode(type: String, decoder: (JsonObject) -> T): List<T> =
            upserts.filter { it.type == type }.mapNotNull { runCatching { decoder(it.payload) }.getOrNull() }

        return DatabaseSnapshot(
            transactions = decode(SyncEntityTypes.TRANSACTIONS) {
                backupJson.decodeFromJsonElement<TransactionEntity>(it)
            },
            categories = decode(SyncEntityTypes.CATEGORIES) {
                backupJson.decodeFromJsonElement<CategoryEntity>(it)
            },
            cards = decode(SyncEntityTypes.CARDS) {
                backupJson.decodeFromJsonElement<CardEntity>(it)
            },
            accountBalances = decode(SyncEntityTypes.ACCOUNT_BALANCES) {
                backupJson.decodeFromJsonElement<AccountBalanceEntity>(it)
            },
            subscriptions = decode(SyncEntityTypes.SUBSCRIPTIONS) {
                backupJson.decodeFromJsonElement<SubscriptionEntity>(it)
            },
            merchantMappings = decode(SyncEntityTypes.MERCHANT_MAPPINGS) {
                backupJson.decodeFromJsonElement<MerchantMappingEntity>(it)
            },
            merchantAliases = decode(SyncEntityTypes.MERCHANT_ALIASES) {
                backupJson.decodeFromJsonElement<MerchantAliasEntity>(it)
            },
            unrecognizedSms = decode(SyncEntityTypes.UNRECOGNIZED_SMS) {
                backupJson.decodeFromJsonElement<UnrecognizedSmsEntity>(it)
            },
            chatMessages = decode(SyncEntityTypes.CHAT_MESSAGES) {
                backupJson.decodeFromJsonElement<ChatMessage>(it)
            },
            rules = decode(SyncEntityTypes.RULES) {
                backupJson.decodeFromJsonElement<RuleEntity>(it)
            },
            ruleApplications = decode(SyncEntityTypes.RULE_APPLICATIONS) {
                backupJson.decodeFromJsonElement<RuleApplicationEntity>(it)
            },
            exchangeRates = decode(SyncEntityTypes.EXCHANGE_RATES) {
                backupJson.decodeFromJsonElement<ExchangeRateEntity>(it)
            },
            budgets = decode(SyncEntityTypes.BUDGETS) {
                backupJson.decodeFromJsonElement<BudgetEntity>(it)
            },
            budgetCategories = decode(SyncEntityTypes.BUDGET_CATEGORIES) {
                backupJson.decodeFromJsonElement<BudgetCategoryEntity>(it)
            },
            transactionSplits = decode(SyncEntityTypes.TRANSACTION_SPLITS) {
                backupJson.decodeFromJsonElement<TransactionSplitEntity>(it)
            },
            bankNotifications = decode(SyncEntityTypes.BANK_NOTIFICATIONS) {
                backupJson.decodeFromJsonElement<BankNotificationEntity>(it)
            },
            loans = decode(SyncEntityTypes.LOANS) {
                backupJson.decodeFromJsonElement<LoanEntity>(it)
            },
            transactionGroups = decode(SyncEntityTypes.TRANSACTION_GROUPS) {
                backupJson.decodeFromJsonElement<TransactionGroupEntity>(it)
            },
            profiles = decode(SyncEntityTypes.PROFILES) {
                backupJson.decodeFromJsonElement<ProfileEntity>(it)
            },
            budgetMonthSnapshots = decode(SyncEntityTypes.BUDGET_MONTH_SNAPSHOTS) {
                backupJson.decodeFromJsonElement<BudgetMonthSnapshotEntity>(it)
            },
            budgetCategoryMonthSnapshots = decode(SyncEntityTypes.BUDGET_CATEGORY_MONTH_SNAPSHOTS) {
                backupJson.decodeFromJsonElement<BudgetCategoryMonthSnapshotEntity>(it)
            },
            tags = decode(SyncEntityTypes.TAGS) {
                backupJson.decodeFromJsonElement<TagEntity>(it)
            },
            transactionTagCrossRefs = decode(SyncEntityTypes.TRANSACTION_TAG_CROSS_REFS) {
                backupJson.decodeFromJsonElement<TransactionTagCrossRef>(it)
            },
            recurringTransactions = decode(SyncEntityTypes.RECURRING_TRANSACTIONS) {
                backupJson.decodeFromJsonElement<RecurringTransactionEntity>(it)
            }
        )
    }

    fun encodePreferences(preferences: PreferencesSnapshot): JsonObject {
        return backupJson.encodeToJsonElement(preferences).jsonObject
    }

    fun decodePreferences(patch: JsonObject): PreferencesSnapshot {
        return backupJson.decodeFromJsonElement(patch)
    }

    private inline fun <reified T> entity(
        type: String,
        key: String,
        value: T,
        updatedAt: String? = null
    ): SyncEntity = SyncEntity(
        type = type,
        key = key,
        payload = backupJson.encodeToJsonElement(value).jsonObject,
        updatedAt = updatedAt
    )
}
