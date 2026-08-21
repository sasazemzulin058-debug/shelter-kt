package net.typeblog.shelter.ui.main

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import net.typeblog.shelter.data.repository.AppNotFoundException
import net.typeblog.shelter.data.repository.AppRepository
import net.typeblog.shelter.data.repository.InstallResult
import net.typeblog.shelter.data.repository.ServiceUnavailableException
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.services.IShelterService
import javax.inject.Inject

/**
 * State holder for the app-list screen.
 *
 * Depends on [AppRepository] (the service gateway that reads the current profile's
 * Shell service handles) and [SettingsStore] (auto-freeze list). It owns no Activity,
 * ServiceConnection, or Compose references: launch / unfreeze-shortcut intents are
 * derived by the UI layer from the exposed state because they require a Context.
 *
 * All repository calls run in [viewModelScope] and are therefore cancelled when the
 * ViewModel is cleared; the repository itself suppresses late AIDL callbacks on
 * cancellation ([AppRepository] contract).
 */
@HiltViewModel
class AppListViewModel @Inject constructor(
    private val repo: AppRepository,
    private val settings: SettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _state = MutableStateFlow(AppListUiState())
    val state: StateFlow<AppListUiState> = _state.asStateFlow()

    /** True when this list manages the work profile (multi-select + cross-profile ops). */
    var isRemote: Boolean = savedStateHandle[KEY_IS_REMOTE] ?: false

    /** The last "show all" flag is kept so [refresh] can be called parameterless. */
    private var showAll: Boolean = savedStateHandle[KEY_SHOW_ALL] ?: false
    private var refreshing = false

    /**
     * Bind this profile's runtime service handles and (re)load the list. Called by the UI
     * once the activity's ServiceConnections are alive; no-op-safe on an unconfigured repo
     * (calls then surface [ServiceUnavailableException]).
     */
    fun configureServices(service: IShelterService, otherService: IShelterService?) {
        repo.configure(service, otherService, pruneAutoFreezeOnList = isRemote)
        refresh()
    }

    /** Freshly loaded icon for a package; call from a CoroutineScope owned by the UI. */
    suspend fun loadIcon(packageName: String): android.graphics.Bitmap =
        repo.loadIcon(packageName)

    fun refresh(showAll: Boolean = this.showAll) {
        if (refreshing) return
        if (_state.value.multiSelectMode) return // original: no refresh mid multi-select
        refreshing = true
        this.showAll = showAll
        _state.update { it.copy(refreshing = true, error = null) }

        viewModelScope.launch {
            try {
                val apps = repo.getApps(showAll = showAll)
                val widgets = if (isRemote) repo.getCrossProfileWidgetProviders() else emptyList()
                val packages =
                    if (isRemote) repo.getCrossProfilePackages() else emptyList()
                _state.update {
                    it.copy(
                        apps = apps,
                        refreshing = false,
                        crossProfileWidgetProviders = widgets.toSet(),
                        crossProfilePackages = packages.toSet(),
                    )
                }
            } catch (e: ServiceUnavailableException) {
                fail(AppListError.ServiceUnavailable, e)
            } catch (e: Exception) {
                fail(AppListError.Generic, e)
            } finally {
                refreshing = false
            }
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    // ------------------------------------------------------------------ multi-select

    fun setMultiSelectMode(enabled: Boolean) {
        _state.update { it.copy(multiSelectMode = enabled, selected = if (enabled) it.selected else emptySet()) }
    }

    fun toggleSelection(packageName: String) {
        _state.update { s ->
            val selected = s.selected.toMutableSet()
            if (!selected.add(packageName)) selected.remove(packageName)
            s.copy(selected = selected)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selected = emptySet()) }
    }

    // ------------------------------------------------------------------ single-app mutations

    fun clone(packageName: String) = mutate {
        when (repo.installApp(packageName)) {
            is InstallResult.Ok -> null
            is InstallResult.CannotInstallSystemApp -> AppListError.Generic
            is InstallResult.Other -> AppListError.Generic
        }
    }

    fun uninstall(packageName: String) = mutate {
        when (repo.uninstallApp(packageName)) {
            is InstallResult.Ok -> null
            is InstallResult.CannotInstallSystemApp -> AppListError.Generic
            is InstallResult.Other -> AppListError.Generic
        }
    }

    fun freeze(packageName: String) = mutate { repo.freeze(packageName); null }

    fun unfreeze(packageName: String) = mutate { repo.unfreeze(packageName); null }

    /** Toggle the work-profile auto-freeze membership; the list reflects it on next refresh. */
    fun toggleAutoFreeze(packageName: String) {
        viewModelScope.launch {
            try {
                if (settings.syncAutoFreezeContains(packageName)) {
                    settings.removeFromAutoFreeze(packageName)
                } else {
                    settings.addToAutoFreeze(packageName)
                }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(error = AppListError.Generic) }
            }
        }
    }

    fun setCrossProfileWidget(packageName: String, enabled: Boolean) = mutate {
        val ok = repo.setCrossProfileWidgetProviderEnabled(packageName, enabled)
        if (ok) {
            _state.update { s ->
                val set = s.crossProfileWidgetProviders.toMutableSet()
                if (enabled) set.add(packageName) else set.remove(packageName)
                s.copy(crossProfileWidgetProviders = set)
            }
        }
        if (ok) null else AppListError.Generic
    }

    /** Toggle cross-profile interaction membership (Android R+ only). */
    fun toggleCrossProfileInteraction(packageName: String) {
        viewModelScope.launch {
            runBusy {
                val current = _state.value.crossProfilePackages
                val next = if (packageName in current) current - packageName else current + packageName
                try {
                    repo.setCrossProfilePackages(next.toList())
                    _state.update { it.copy(crossProfilePackages = next) }
                } catch (e: ServiceUnavailableException) {
                    _state.update { it.copy(error = AppListError.ServiceUnavailable) }
                } catch (e: Exception) {
                    _state.update { it.copy(error = AppListError.Generic) }
                }
            }
        }
    }

    /** Unfreeze several linked packages (used by the multi-select shortcut path). */
    fun unfreezeMany(packages: List<String>) = mutate {
        packages.forEach { repo.unfreeze(it) }
        null
    }

    // ------------------------------------------------------------------ helpers

    /** Run one serialized mutation: clear error, mark busy, execute, then refresh. */
    private fun mutate(block: suspend () -> AppListError?) {
        viewModelScope.launch {
            runBusy {
                var opError: AppListError? = null
                try {
                    opError = block()
                } catch (e: ServiceUnavailableException) {
                    opError = AppListError.ServiceUnavailable
                } catch (e: AppNotFoundException) {
                    opError = AppListError.AppNotFound
                } catch (e: Exception) {
                    opError = AppListError.Generic
                }
                if (opError != null) {
                    _state.update { it.copy(error = opError) }
                } else {
                    refresh()
                }
            }
        }
    }

    private suspend fun runBusy(block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    private fun fail(error: AppListError, cause: Throwable) {
        _state.update { it.copy(refreshing = false, error = error) }
    }

    private fun MutableStateFlow<AppListUiState>.update(transform: (AppListUiState) -> AppListUiState) {
        value = transform(value)
    }

    private companion object {
        const val KEY_IS_REMOTE = "is_remote"
        const val KEY_SHOW_ALL = "show_all"
    }
}
