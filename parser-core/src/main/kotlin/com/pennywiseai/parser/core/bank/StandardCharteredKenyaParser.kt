package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.ParsedTransaction
import com.pennywiseai.parser.core.TransactionType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parser for Standard Chartered Kenya credit-card SMS.
 *
 * Kenya alerts are wrapped as reward-points notices but carry the real spend:
 *
 * "Dear Client, you have earned reward points for a transaction of KES 2850.00
 *  made on your Credit Card ending 8442 at KONQA KITCHEN on 18/09/2026"
 *
 * Must return null for India/Pakistan/Nigeria Standard Chartered SMS that share
 * STANCHART / SCBANK senders so those parsers still win.
 */
class StandardCharteredKenyaParser : BankParser() {

    override fun getBankName() = "Standard Chartered Bank Kenya"

    override fun getCurrency() = "KES"

    override fun canHandle(sender: String): Boolean {
        val upper = sender.uppercase()
        return upper.contains("STANDARD CHARTERED") ||
            upper.contains("STANDARDCHARTERED") ||
            upper.contains("STANCHART") ||
            upper.contains("SCBANK")
    }

    override fun parse(smsBody: String, sender: String, timestamp: Long): ParsedTransaction? {
        if (!isKenyaCardSpend(smsBody)) return null
        val parsed = super.parse(smsBody, sender, timestamp) ?: return null
        val smsDate = extractTransactionDateMillis(smsBody)
        return parsed.copy(timestamp = smsDate ?: timestamp)
    }

    override fun isTransactionMessage(message: String): Boolean {
        if (isKenyaCardSpend(message)) return true
        return super.isTransactionMessage(message)
    }

    override fun extractAmount(message: String): BigDecimal? {
        AMOUNT_PATTERN.find(message)?.let { match ->
            val amount = match.groupValues[1].replace(",", "")
            return amount.toBigDecimalOrNull()
        }
        return null
    }

    override fun extractMerchant(message: String, sender: String): String? {
        MERCHANT_DATE_PATTERN.find(message)?.let { match ->
            val merchant = cleanMerchantName(match.groupValues[1].trim())
            if (isValidMerchantName(merchant)) return merchant
        }
        return super.extractMerchant(message, sender)
    }

    override fun extractAccountLast4(message: String): String? {
        CARD_LAST4_PATTERN.find(message)?.let { match ->
            return extractLast4Digits(match.groupValues[1])
        }
        return super.extractAccountLast4(message)
    }

    override fun extractTransactionType(message: String): TransactionType? {
        if (isKenyaCardSpend(message)) return TransactionType.EXPENSE
        return super.extractTransactionType(message)
    }

    override fun detectIsCard(message: String): Boolean {
        if (isKenyaCardSpend(message)) return true
        return super.detectIsCard(message)
    }

    private fun isKenyaCardSpend(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("transaction of kes") && lower.contains("credit card ending")
    }

    private fun extractTransactionDateMillis(message: String): Long? {
        val match = MERCHANT_DATE_PATTERN.find(message) ?: return null
        val dateText = match.groupValues[2]
        return try {
            val date = LocalDate.parse(dateText, DATE_FORMAT)
            date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val AMOUNT_PATTERN = Regex(
            """transaction of KES\s*([0-9,]+(?:\.\d{1,2})?)""",
            RegexOption.IGNORE_CASE
        )
        val CARD_LAST4_PATTERN = Regex(
            """Credit Card ending\s+(\d{4})""",
            RegexOption.IGNORE_CASE
        )
        val MERCHANT_DATE_PATTERN = Regex(
            """at\s+(.+?)\s+on\s+(\d{2}/\d{2}/\d{4})""",
            RegexOption.IGNORE_CASE
        )
        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
