package com.pennywiseai.tracker.data.sync

import com.pennywiseai.tracker.data.database.entity.TransactionEntity
import com.pennywiseai.tracker.data.database.entity.TransactionType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class SyncMergeTest {

    @Test
    fun equalTimestampStillAppliesRemoteEdit() {
        val originalTime = LocalDateTime.of(2026, 9, 18, 10, 0)
        val local = sample(category = "Food", updatedAt = originalTime)
        val remote = sample(category = "Shopping", updatedAt = originalTime)
        assertTrue(SyncMerge.shouldReplaceLocalTransaction(local, remote))
    }

    @Test
    fun newerRemoteWins() {
        val local = sample(category = "Food", updatedAt = LocalDateTime.of(2026, 9, 18, 10, 0))
        val remote = sample(category = "Shopping", updatedAt = LocalDateTime.of(2026, 9, 18, 10, 1))
        assertTrue(SyncMerge.shouldReplaceLocalTransaction(local, remote))
    }

    @Test
    fun olderRemoteDoesNotOverwriteLocalEdit() {
        val local = sample(category = "Shopping", updatedAt = LocalDateTime.of(2026, 9, 18, 10, 1))
        val remote = sample(category = "Food", updatedAt = LocalDateTime.of(2026, 9, 18, 10, 0))
        assertFalse(SyncMerge.shouldReplaceLocalTransaction(local, remote))
    }

    @Test
    fun doesNotResurrectDeletedLocalRow() {
        val local = sample(category = "Food", updatedAt = LocalDateTime.of(2026, 9, 18, 10, 0), deleted = true)
        val remote = sample(category = "Shopping", updatedAt = LocalDateTime.of(2026, 9, 18, 10, 1))
        assertFalse(SyncMerge.shouldReplaceLocalTransaction(local, remote))
    }

    private fun sample(
        category: String,
        updatedAt: LocalDateTime,
        deleted: Boolean = false
    ) = TransactionEntity(
        id = 1,
        amount = BigDecimal("2850.00"),
        merchantName = "KONQA KITCHEN",
        category = category,
        transactionType = TransactionType.EXPENSE,
        dateTime = LocalDateTime.of(2026, 9, 18, 0, 0),
        transactionHash = "hash-kenya-1",
        currency = "KES",
        isDeleted = deleted,
        createdAt = LocalDateTime.of(2026, 9, 18, 1, 0),
        updatedAt = updatedAt
    )
}
