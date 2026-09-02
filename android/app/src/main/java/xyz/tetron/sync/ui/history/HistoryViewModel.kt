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
import kotlinx.coroutines.launch
import xyz.tetron.sync.AppContainer
import xyz.tetron.sync.pipeline.FileRunHistoryStore
import xyz.tetron.sync.pipeline.RunRecord
import xyz.tetron.sync.pipeline.TransferredFileLine

/** SYNC-009 History screen state: the rotating run log ([runs], newest
 *  first) and the persisted file list of the latest run ([latestRunFiles],
 *  for the top row's expander). */
data class HistoryUiState(
    val runs: List<RunRecord> = emptyList(),
    val latestRunFiles: List<TransferredFileLine> = emptyList(),
)

/**
 * SYNC-009 History screen (2026-09-02): the rotating run log from
 * [xyz.tetron.sync.pipeline.RunHistoryStore]. Polls the same way
 * [xyz.tetron.sync.ui.home.HomeViewModel] does -- there is no observable
 * form of the store, and a background-triggered run should still update
 * this screen while it is open.
 */
class HistoryViewModel(private val container: AppContainer) : ViewModel() {

    val uiState: StateFlow<HistoryUiState> = flow {
        while (true) {
            emit(
                HistoryUiState(
                    runs = container.historyStore.recentRuns(FileRunHistoryStore.MAX_RUNS),
                    latestRunFiles = container.runFileLog.read(),
                ),
            )
            delay(POLL_INTERVAL_MILLIS)
        }
    }.flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    /** SYNC-009 "Clear history": drops every row but the most recent. The
     *  poll picks up the change on its next tick. Informational only -- it
     *  does not touch the receiver, `--partial` resume state, or scope. */
    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) { container.historyStore.clear() }
    }

    companion object {
        private const val POLL_INTERVAL_MILLIS = 3_000L
    }
}
