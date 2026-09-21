package com.pennywiseai.parser.core.bank

import com.pennywiseai.parser.core.TransactionType
import com.pennywiseai.parser.core.test.ExpectedTransaction
import com.pennywiseai.parser.core.test.ParserTestCase
import com.pennywiseai.parser.core.test.ParserTestUtils
import com.pennywiseai.parser.core.test.SimpleTestCase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.math.BigDecimal

class StandardCharteredKenyaParserTest {

    private val parser = StandardCharteredKenyaParser()

    @TestFactory
    fun `standard chartered kenya parser handles reward-wrapped card spends`(): List<DynamicTest> {
        ParserTestUtils.printTestHeader(
            parserName = "Standard Chartered Bank Kenya",
            bankName = parser.getBankName(),
            currency = parser.getCurrency()
        )

        val cases = listOf(
            ParserTestCase(
                name = "KES card spend - KONQA KITCHEN 2850",
                message = "Dear Client, you have earned reward points for a transaction of KES 2850.00 made on your Credit Card ending 8442 at KONQA KITCHEN on 18/09/2026",
                sender = "Standard Chartered",
                expected = ExpectedTransaction(
                    amount = BigDecimal("2850.00"),
                    currency = "KES",
                    type = TransactionType.EXPENSE,
                    merchant = "KONQA KITCHEN",
                    accountLast4 = "8442",
                    isFromCard = true
                )
            ),
            ParserTestCase(
                name = "KES card spend - KONQA KITCHEN 500",
                message = "Dear Client, you have earned reward points for a transaction of KES 500.00 made on your Credit Card ending 8442 at KONQA KITCHEN on 18/09/2026",
                sender = "STANCHART",
                expected = ExpectedTransaction(
                    amount = BigDecimal("500.00"),
                    currency = "KES",
                    type = TransactionType.EXPENSE,
                    merchant = "KONQA KITCHEN",
                    accountLast4 = "8442",
                    isFromCard = true
                )
            ),
            ParserTestCase(
                name = "KES card spend - SHELL PETROL ST",
                message = "Dear Client, you have earned reward points for a transaction of KES 10000.00 made on your Credit Card ending 8442 at SHELL PETROL ST on 19/09/2026",
                sender = "SCBANK",
                expected = ExpectedTransaction(
                    amount = BigDecimal("10000.00"),
                    currency = "KES",
                    type = TransactionType.EXPENSE,
                    merchant = "SHELL PETROL ST",
                    accountLast4 = "8442",
                    isFromCard = true
                )
            ),
            ParserTestCase(
                name = "KES card spend - Jaff's Optical",
                message = "Dear Client, you have earned reward points for a transaction of KES 8500.00 made on your Credit Card ending 8442 at Jaff's Optical on 19/09/2026",
                sender = "Standard Chartered",
                expected = ExpectedTransaction(
                    amount = BigDecimal("8500.00"),
                    currency = "KES",
                    type = TransactionType.EXPENSE,
                    merchant = "Jaff's Optical",
                    accountLast4 = "8442",
                    isFromCard = true
                )
            ),
            ParserTestCase(
                name = "KES card spend - PALACE INSTANBU",
                message = "Dear Client, you have earned reward points for a transaction of KES 9950.00 made on your Credit Card ending 8442 at PALACE INSTANBU on 19/09/2026",
                sender = "Standard Chartered",
                expected = ExpectedTransaction(
                    amount = BigDecimal("9950.00"),
                    currency = "KES",
                    type = TransactionType.EXPENSE,
                    merchant = "PALACE INSTANBU",
                    accountLast4 = "8442",
                    isFromCard = true
                )
            )
        )

        val handleCases = listOf(
            "Standard Chartered" to true,
            "STANCHART" to true,
            "SCBANK" to true,
            "VM-SCBANK-S" to true,
            "SC_ALERT" to false,
            "MPESA" to false
        )

        return ParserTestUtils.runTestSuite(
            parser = parser,
            testCases = cases,
            handleCases = handleCases,
            suiteName = "Standard Chartered Kenya Parser Tests"
        )
    }

    @TestFactory
    fun `factory resolves standard chartered kenya`(): List<DynamicTest> {
        val cases = listOf(
            SimpleTestCase(
                bankName = "Standard Chartered Bank Kenya",
                sender = "Standard Chartered",
                currency = "KES",
                message = "Dear Client, you have earned reward points for a transaction of KES 2850.00 made on your Credit Card ending 8442 at KONQA KITCHEN on 18/09/2026",
                expected = ExpectedTransaction(
                    amount = BigDecimal("2850.00"),
                    currency = "KES",
                    type = TransactionType.EXPENSE,
                    merchant = "KONQA KITCHEN",
                    accountLast4 = "8442",
                    isFromCard = true
                ),
                shouldHandle = true
            )
        )
        return ParserTestUtils.runFactoryTestSuite(cases, "Factory smoke tests")
    }

    @Test
    fun `india standard chartered sms is not claimed by kenya parser`() {
        val indiaSms = "Your a/c XX3421 is debited for Rs. 302.00 on 03-12-2025 15:49 and credited to a/c XX1465 (UPI Ref no 487597904232)"
        assertNull(parser.parse(indiaSms, "VM-SCBANK-S", System.currentTimeMillis()))

        val parsed = BankParserFactory.parse(indiaSms, "VM-SCBANK-S", System.currentTimeMillis())
        assertNotNull(parsed)
        assertEquals("Standard Chartered Bank", parsed!!.bankName)
    }

    @Test
    fun `nigeria standard chartered sms is not claimed by kenya parser`() {
        val nigeriaSms = "Debit Alert! Acct:xxxxxx1234, Amt:NGN1000.00, Desc:TEST, Date:2026-08-24, Bal:NGN1500000.00"
        assertNull(parser.parse(nigeriaSms, "StanChart", System.currentTimeMillis()))
    }
}
