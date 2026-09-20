package com.pennywiseai.tracker.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class SyncLocalChange {
    data object Dirty : SyncLocalChange()
    data object PreferencesDirty : SyncLocalChange()
    data class Delete(val type: String, val key: String) : SyncLocalChange()
}

@Singleton
class SyncChangeBus @Inject constructor() {
    private val _changes = MutableSharedFlow<SyncLocalChange>(extraBufferCapacity = 64)
    val changes: SharedFlow<SyncLocalChange> = _changes.asSharedFlow()

    fun markDirty() {
        _changes.tryEmit(SyncLocalChange.Dirty)
    }

    fun markPreferencesDirty() {
        _changes.tryEmit(SyncLocalChange.PreferencesDirty)
    }

    fun markDeleted(type: String, key: String) {
        _changes.tryEmit(SyncLocalChange.Delete(type, key))
    }
}
