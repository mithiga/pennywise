package com.pennywiseai.sync

import kotlinx.serialization.Serializable

@Serializable
data class DashboardSummary(
    val income: Map<String, String>,
    val expense: Map<String, String>,
    val accounts: List<DashboardAccount>,
    val recent: List<DashboardTransaction>
)

@Serializable
data class DashboardTransaction(
    val hash: String,
    val amount: String,
    val currency: String,
    val merchantName: String,
    val description: String? = null,
    val category: String,
    val transactionType: String,
    val dateTime: String,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val fromAccount: String? = null,
    val toAccount: String? = null,
    val excludedFromAnalytics: Boolean = false,
    val updatedAt: String? = null,
    val smsBody: String? = null,
    val smsSender: String? = null
)

@Serializable
data class DashboardAccount(
    val bankName: String,
    val accountLast4: String,
    val balance: String? = null,
    val currency: String,
    val alias: String? = null,
    val isCreditCard: Boolean = false,
    val transactionCount: Int = 0
)

@Serializable
data class DashboardTransactionWrite(
    val amount: String? = null,
    val currency: String? = null,
    val merchantName: String? = null,
    val description: String? = null,
    val category: String? = null,
    val transactionType: String? = null,
    val dateTime: String? = null,
    val bankName: String? = null,
    val accountNumber: String? = null,
    val fromAccount: String? = null,
    val toAccount: String? = null,
    val excludedFromAnalytics: Boolean? = null
)

@Serializable
data class DashboardCategories(
    val names: List<String>
)

@Serializable
data class DashboardAccountsResponse(
    val accounts: List<DashboardAccount>
)

@Serializable
data class DashboardTransactionsResponse(
    val transactions: List<DashboardTransaction>
)

@Serializable
data class DashboardWriteResult(
    val revision: Long,
    val hash: String,
    val transaction: DashboardTransaction
)

@Serializable
data class ErrorBody(val error: String)

@Serializable
data class DashboardLoginRequest(
    val token: String? = null,
    val channel: String? = null
)

@Serializable
data class DashboardVerifyRequest(
    val challengeId: String? = null,
    val code: String? = null
)

class DashboardException(val status: io.ktor.http.HttpStatusCode, override val message: String) : RuntimeException(message)
