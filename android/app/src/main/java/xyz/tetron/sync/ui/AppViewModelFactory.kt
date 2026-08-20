// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.sync.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import xyz.tetron.sync.AppContainer
import xyz.tetron.sync.ui.history.HistoryViewModel
import xyz.tetron.sync.ui.home.HomeViewModel

/** SYNC-009: hand-rolled `ViewModelProvider.Factory` -- no DI framework,
 *  same convention as [AppContainer] itself. */
class AppViewModelFactory(private val container: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
        HomeViewModel::class.java -> HomeViewModel(container) as T
        HistoryViewModel::class.java -> HistoryViewModel(container) as T
        else -> throw IllegalArgumentException("Unknown ViewModel class: $modelClass")
    }
}
