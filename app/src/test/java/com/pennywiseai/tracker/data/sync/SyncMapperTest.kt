package com.pennywiseai.tracker.data.sync

import com.pennywiseai.tracker.data.backup.DatabaseSnapshot
import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class SyncMapperTest {

    @Test
    fun transactionSnapshotRoundTripPreservesHashAndAmount() {
        val original = TransactionEntity(
            id = 7,
            amount = BigDecimal("2850.00"),
            merchantName = "KONQA KITCHEN",
            category = "Food",
            transactionType = TransactionType.EXPENSE,
            dateTime = LocalDateTime.of(2026, 9, 18, 0, 0),
            transactionHash = "hash-kenya-1",
            currency = "KES",
            createdAt = LocalDateTime.of(2026, 9, 18, 1, 0),
            updatedAt = LocalDateTime.of(2026, 9, 18, 1, 0)
        )
        val upserts = SyncMapper.toUpserts(DatabaseSnapshot(transactions = listOf(original)))
        assertEquals(1, upserts.size)
        assertEquals(SyncEntityTypes.TRANSACTIONS, upserts[0].type)
        assertEquals("hash-kenya-1", upserts[0].key)

        val restored = SyncMapper.toSnapshot(upserts).transactions.single()
        assertEquals(0L, restored.id)
        assertEquals(original.transactionHash, restored.transactionHash)
        assertEquals(0, original.amount.compareTo(restored.amount))
        assertEquals(original.merchantName, restored.merchantName)
        assertEquals(original.currency, restored.currency)
    }

    @Test
    fun emptySnapshotProducesNoUpserts() {
        assertTrue(SyncMapper.toUpserts(DatabaseSnapshot()).isEmpty())
    }
}
