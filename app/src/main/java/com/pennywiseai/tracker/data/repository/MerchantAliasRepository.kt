package com.pennywiseai.tracker.data.repository

import com.pennywiseai.tracker.data.database.dao.MerchantAliasDao
import com.pennywiseai.tracker.data.database.entity.MerchantAliasEntity
import com.pennywiseai.tracker.data.sync.SyncChangeBus
import kotlinx.coroutines.flow.Flow
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantAliasRepository @Inject constructor(
    private val merchantAliasDao: MerchantAliasDao,
    private val syncChangeBus: SyncChangeBus
) {

    suspend fun getAliasForMerchant(merchantName: String): String? {
        return merchantAliasDao.getAliasForMerchant(merchantName)
    }

    suspend fun setAlias(merchantName: String, alias: String) {
        merchantAliasDao.insertOrUpdateAlias(
            MerchantAliasEntity(
                merchantName = merchantName,
                alias = alias,
                updatedAt = LocalDateTime.now()
            )
        )
        syncChangeBus.markDirty()
    }

    suspend fun removeAlias(merchantName: String) {
        merchantAliasDao.deleteAlias(merchantName)
        syncChangeBus.markDirty()
    }

    fun getAllAliases(): Flow<List<MerchantAliasEntity>> {
        return merchantAliasDao.getAllAliases()
    }

    suspend fun getAliasCount(): Int {
        return merchantAliasDao.getAliasCount()
    }

    /**
     * Returns all merchant→alias mappings as a plain Map for O(1) lookup.
     * Used by display-resolution code to pre-load the cache once instead of
     * hitting the DB once per transaction.
     */
    suspend fun getAllAliasesAsMap(): Map<String, String> {
        return merchantAliasDao.getAllAliasesList().associate { it.merchantName to it.alias }
    }
}
