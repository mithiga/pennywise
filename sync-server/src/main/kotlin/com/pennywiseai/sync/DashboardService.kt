package com.pennywiseai.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class DashboardService(private val store: SyncStore) {

    fun summary(): DashboardSummary {
        val transactions = allTransactions()
            .filterNot { it.excludedFromAnalytics }
        val month = YearMonth.now()
        val inMonth = transactions.filter { it.yearMonth() == month }
        val income = totalsByCurrency(inMonth.filter { it.transactionType == "INCOME" })
        val expense = totalsByCurrency(
            inMonth.filter { it.transactionType == "EXPENSE" || it.transactionType == "CREDIT" }
        )
        val recent = transactions
            .sortedByDescending { it.dateTime }
            .take(8)
        return DashboardSummary(
            income = income,
            expense = expense,
            accounts = accounts(),
            recent = recent
        )
    }

    fun listTransactions(
        query: String?,
        type: String?,
        account: String?,
        from: String?,
        to: String?
    ): List<DashboardTransaction> {
        val fromDt = from?.let { parseDateTime(it, startOfDay = true) }
        val toDt = to?.let { parseDateTime(it, startOfDay = false) }
        val q = query?.trim()?.lowercase().orEmpty()
        return allTransactions()
            .asSequence()
            .filter { tx ->
                q.isEmpty() ||
                    tx.merchantName.lowercase().contains(q) ||
                    tx.category.lowercase().contains(q) ||
                    (tx.description?.lowercase()?.contains(q) == true) ||
                    (tx.bankName?.lowercase()?.contains(q) == true)
            }
            .filter { type.isNullOrBlank() || it.transactionType.equals(type, ignoreCase = true) }
            .filter { account.isNullOrBlank() || accountKey(it.bankName, it.accountNumber) == account }
            .filter { fromDt == null || it.dateTime >= fromDt }
            .filter { toDt == null || it.dateTime <= toDt }
            .sortedByDescending { it.dateTime }
            .toList()
    }

    fun getTransaction(hash: String): DashboardTransaction {
        val entity = store.latestByKey(SyncEntityTypes.TRANSACTIONS, hash)
            ?: throw DashboardException(io.ktor.http.HttpStatusCode.NotFound, "transaction not found")
        return entity.toTransaction()
            ?: throw DashboardException(io.ktor.http.HttpStatusCode.NotFound, "transaction not found")
    }

    fun updateTransaction(hash: String, patch: DashboardTransactionWrite): DashboardWriteResult {
        val existing = store.latestByKey(SyncEntityTypes.TRANSACTIONS, hash)
            ?: throw DashboardException(io.ktor.http.HttpStatusCode.NotFound, "transaction not found")
        val now = LocalDateTime.now().format(TIMESTAMP)
        val merged = mergePayload(existing.payload, patch, now)
        val entity = SyncEntity(
            type = SyncEntityTypes.TRANSACTIONS,
            key = hash,
            payload = merged,
            updatedAt = now
        )
        val revision = store.applyLocal(
            SyncRequest(deviceId = SyncEntityTypes.DASHBOARD_DEVICE, upserts = listOf(entity))
        )
        val tx = entity.toTransaction()
            ?: throw DashboardException(io.ktor.http.HttpStatusCode.InternalServerError, "invalid payload")
        return DashboardWriteResult(revision = revision, hash = hash, transaction = tx)
    }

    fun createTransaction(body: DashboardTransactionWrite): DashboardWriteResult {
        val amount = body.amount?.trim().orEmpty()
        val merchant = body.merchantName?.trim().orEmpty()
        val dateTime = body.dateTime?.trim().orEmpty()
        if (amount.isEmpty() || merchant.isEmpty() || dateTime.isEmpty()) {
            throw DashboardException(
                io.ktor.http.HttpStatusCode.BadRequest,
                "amount, merchantName, and dateTime are required"
            )
        }
        val parsedAmount = runCatching { BigDecimal(amount) }.getOrNull()
            ?: throw DashboardException(io.ktor.http.HttpStatusCode.BadRequest, "invalid amount")
        if (parsedAmount <= BigDecimal.ZERO) {
            throw DashboardException(io.ktor.http.HttpStatusCode.BadRequest, "amount must be positive")
        }
        val hash = manualHash(parsedAmount.toPlainString(), merchant, dateTime)
        if (store.latestByKey(SyncEntityTypes.TRANSACTIONS, hash) != null) {
            throw DashboardException(io.ktor.http.HttpStatusCode.Conflict, "transaction already exists")
        }
        val now = LocalDateTime.now().format(TIMESTAMP)
        val payload = buildJsonObject {
            put("id", 0)
            put("amount", parsedAmount.toPlainString())
            put("merchantName", merchant)
            put("category", body.category?.trim()?.ifBlank { null } ?: "Others")
            put("transactionType", body.transactionType?.trim()?.ifBlank { null } ?: "EXPENSE")
            put("dateTime", dateTime)
            put("description", body.description)
            put("smsBody", JsonNull)
            put("bankName", body.bankName?.trim() ?: "Manual Entry")
            put("smsSender", JsonNull)
            put("accountNumber", body.accountNumber)
            put("transactionHash", hash)
            put("isRecurring", false)
            put("isDeleted", false)
            put("excludedFromAnalytics", body.excludedFromAnalytics ?: false)
            put("createdAt", now)
            put("updatedAt", now)
            put("currency", body.currency?.trim()?.ifBlank { null } ?: "KES")
            put("fromAccount", body.fromAccount)
            put("toAccount", body.toAccount)
        }
        val entity = SyncEntity(
            type = SyncEntityTypes.TRANSACTIONS,
            key = hash,
            payload = payload,
            updatedAt = now
        )
        val revision = store.applyLocal(
            SyncRequest(deviceId = SyncEntityTypes.DASHBOARD_DEVICE, upserts = listOf(entity))
        )
        val tx = entity.toTransaction()
            ?: throw DashboardException(io.ktor.http.HttpStatusCode.InternalServerError, "invalid payload")
        return DashboardWriteResult(revision = revision, hash = hash, transaction = tx)
    }

    fun deleteTransaction(hash: String): Long {
        store.latestByKey(SyncEntityTypes.TRANSACTIONS, hash)
            ?: throw DashboardException(io.ktor.http.HttpStatusCode.NotFound, "transaction not found")
        return store.applyLocal(
            SyncRequest(
                deviceId = SyncEntityTypes.DASHBOARD_DEVICE,
                deletes = listOf(SyncTombstone(type = SyncEntityTypes.TRANSACTIONS, key = hash))
            )
        )
    }

    fun accounts(): List<DashboardAccount> {
        val txs = allTransactions()
        val counts = txs.groupingBy { accountKey(it.bankName, it.accountNumber) }.eachCount()
        val latestBalances = linkedMapOf<String, Pair<String, DashboardAccount>>()

        store.latestLive(SyncEntityTypes.ACCOUNT_BALANCES).forEach { entity ->
            val bank = entity.payload.str("bankName") ?: return@forEach
            val last4 = entity.payload.str("accountLast4") ?: return@forEach
            val key = accountKey(bank, last4)
            val timestamp = entity.payload.str("timestamp").orEmpty()
            val current = latestBalances[key]
            if (current == null || timestamp > current.first) {
                latestBalances[key] = timestamp to DashboardAccount(
                    bankName = bank,
                    accountLast4 = last4,
                    balance = entity.payload.str("balance"),
                    currency = entity.payload.str("currency") ?: "KES",
                    alias = entity.payload.str("alias"),
                    isCreditCard = entity.payload.bool("isCreditCard"),
                    transactionCount = counts[key] ?: 0
                )
            }
        }

        val byAccount = linkedMapOf<String, DashboardAccount>()
        latestBalances.forEach { (key, pair) -> byAccount[key] = pair.second }

        store.latestLive(SyncEntityTypes.CARDS).forEach { entity ->
            val bank = entity.payload.str("bankName") ?: return@forEach
            val last4 = entity.payload.str("cardLast4") ?: return@forEach
            val key = accountKey(bank, last4)
            if (!byAccount.containsKey(key)) {
                byAccount[key] = DashboardAccount(
                    bankName = bank,
                    accountLast4 = last4,
                    balance = entity.payload.str("currentBalance") ?: entity.payload.str("balance"),
                    currency = entity.payload.str("currency") ?: "KES",
                    alias = entity.payload.str("nickname"),
                    isCreditCard = entity.payload.str("cardType")?.contains("CREDIT", ignoreCase = true) == true,
                    transactionCount = counts[key] ?: 0
                )
            }
        }

        txs.forEach { tx ->
            val last4 = tx.accountNumber ?: return@forEach
            val bank = tx.bankName ?: "Unknown"
            val key = accountKey(bank, last4)
            if (!byAccount.containsKey(key)) {
                byAccount[key] = DashboardAccount(
                    bankName = bank,
                    accountLast4 = last4,
                    currency = tx.currency,
                    transactionCount = counts[key] ?: 0
                )
            } else {
                val existing = byAccount.getValue(key)
                if (existing.transactionCount == 0) {
                    byAccount[key] = existing.copy(transactionCount = counts[key] ?: 0)
                }
            }
        }

        return byAccount.values.sortedWith(compareBy({ it.bankName.lowercase() }, { it.accountLast4 }))
    }

    fun categories(): List<String> {
        val fromTable = store.latestLive(SyncEntityTypes.CATEGORIES)
            .mapNotNull { it.payload.str("name") }
        val fromTxs = allTransactions().map { it.category }
        return (fromTable + fromTxs + DEFAULT_CATEGORIES)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
    }

    private fun allTransactions(): List<DashboardTransaction> =
        store.latestLive(SyncEntityTypes.TRANSACTIONS).mapNotNull { it.toTransaction() }

    private fun totalsByCurrency(items: List<DashboardTransaction>): Map<String, String> {
        val sums = linkedMapOf<String, BigDecimal>()
        for (item in items) {
            val amount = runCatching { BigDecimal(item.amount) }.getOrDefault(BigDecimal.ZERO)
            sums[item.currency] = (sums[item.currency] ?: BigDecimal.ZERO).add(amount)
        }
        return sums.mapValues { it.value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() }
    }

    companion object {
        private val TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val DEFAULT_CATEGORIES = listOf(
            "Food & Dining", "Groceries", "Transport", "Shopping", "Bills & Utilities",
            "Entertainment", "Health", "Others", "Income", "Transfer"
        )

        fun manualHash(amount: String, merchant: String, dateTime: String): String {
            val data = "MANUAL_${amount}_${merchant}_${dateTime}"
            return MessageDigest.getInstance("MD5")
                .digest(data.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
    }
}

private fun accountKey(bank: String?, last4: String?): String =
    "${bank.orEmpty()}||${last4.orEmpty()}"

private fun DashboardTransaction.yearMonth(): YearMonth? =
    runCatching { YearMonth.from(LocalDateTime.parse(dateTime)) }.getOrNull()

private fun parseDateTime(raw: String, startOfDay: Boolean): String {
    val trimmed = raw.trim()
    if (trimmed.length == 10) {
        val date = LocalDate.parse(trimmed)
        return if (startOfDay) date.atStartOfDay().toString() else date.atTime(23, 59, 59).toString()
    }
    return trimmed
}

private fun mergePayload(existing: JsonObject, patch: DashboardTransactionWrite, now: String): JsonObject {
    return buildJsonObject {
        existing.forEach { (key, value) -> put(key, value) }
        patch.amount?.let { put("amount", it) }
        patch.currency?.let { put("currency", it) }
        patch.merchantName?.let { put("merchantName", it) }
        if (patch.description != null) put("description", patch.description)
        patch.category?.let { put("category", it) }
        patch.transactionType?.let { put("transactionType", it) }
        patch.dateTime?.let { put("dateTime", it) }
        if (patch.bankName != null) put("bankName", patch.bankName)
        if (patch.accountNumber != null) put("accountNumber", patch.accountNumber)
        if (patch.fromAccount != null) put("fromAccount", patch.fromAccount)
        if (patch.toAccount != null) put("toAccount", patch.toAccount)
        patch.excludedFromAnalytics?.let { put("excludedFromAnalytics", it) }
        put("updatedAt", now)
        put("id", 0)
    }
}

private fun SyncEntity.toTransaction(): DashboardTransaction? {
    val hash = payload.str("transactionHash") ?: key
    val amount = payload.str("amount") ?: return null
    val merchant = payload.str("merchantName") ?: return null
    val type = payload.str("transactionType") ?: "EXPENSE"
    val dateTime = payload.str("dateTime") ?: return null
    return DashboardTransaction(
        hash = hash,
        amount = amount,
        currency = payload.str("currency") ?: "KES",
        merchantName = merchant,
        description = payload.str("description"),
        category = payload.str("category") ?: "Others",
        transactionType = type,
        dateTime = dateTime,
        bankName = payload.str("bankName"),
        accountNumber = payload.str("accountNumber"),
        fromAccount = payload.str("fromAccount"),
        toAccount = payload.str("toAccount"),
        excludedFromAnalytics = payload.bool("excludedFromAnalytics"),
        updatedAt = payload.str("updatedAt") ?: updatedAt
    )
}

private fun JsonObject.str(key: String): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value.jsonPrimitive.contentOrNull?.takeIf { it.isNotBlank() }
}

private fun JsonObject.bool(key: String): Boolean {
    val value = this[key] ?: return false
    if (value is JsonNull) return false
    val primitive = value.jsonPrimitive
    return primitive.content.equals("true", ignoreCase = true)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: String?) {
    if (value == null) put(key, JsonNull) else put(key, value)
}
