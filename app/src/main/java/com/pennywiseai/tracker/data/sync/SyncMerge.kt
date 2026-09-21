package com.pennywiseai.tracker.data.sync

import com.pennywiseai.tracker.data.database.entity.TransactionEntity

object SyncMerge {
    /**
     * Last-write-wins for a transaction that already exists locally (matched by
     * [TransactionEntity.transactionHash]). Equal timestamps still apply so an
     * edit that forgot to bump [TransactionEntity.updatedAt] is not dropped.
     */
    fun shouldReplaceLocalTransaction(local: TransactionEntity, remote: TransactionEntity): Boolean {
        if (local.isDeleted) return false
        return !remote.updatedAt.isBefore(local.updatedAt)
    }
}
