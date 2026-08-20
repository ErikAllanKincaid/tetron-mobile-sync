// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import xyz.tetron.sync.AppContainer
import xyz.tetron.sync.pipeline.RunRecord

/**
 * SYNC-009 History screen: [xyz.tetron.sync.pipeline.RunHistoryStore]'s
 * single last-run record (SYNC-005: "last run time" singular, not a log).
 * Polls the same way [xyz.tetron.sync.ui.home.HomeViewModel] polls the
 * bridge -- there is no push/observable form of the store to subscribe to,
 * and a run started from Home/triggered in the background should still
 * update this screen if it happens to be visible.
 */
class HistoryViewModel(private val container: AppContainer) : ViewModel() {
    val lastRun: StateFlow<RunRecord?> = flow {
        while (true) {
            emit(container.historyStore.lastRun())
            delay(POLL_INTERVAL_MILLIS)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    companion object {
        private const val POLL_INTERVAL_MILLIS = 3_000L
    }
}
