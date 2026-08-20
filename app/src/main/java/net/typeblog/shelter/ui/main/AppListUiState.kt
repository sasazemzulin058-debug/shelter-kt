package net.typeblog.shelter.ui.main

import net.typeblog.shelter.data.model.AppInfo

/** Typed failure kinds surfaced by [AppListViewModel]; the UI maps these to localized text. */
enum class AppListError {
    /** Service binder unbound / died mid-call. */
    ServiceUnavailable,

    /** Requested package is no longer in the last loaded list. */
    AppNotFound,

    /** Any other repository failure (e.g. remote exception on a background call). */
    Generic,
}

/**
 * Immutable snapshot of the app-list screen.
 *
 * All app mutation operations (clone, uninstall, freeze, unfreeze, toggles) are
 * serialized through [busy] so the UI can disable controls while one call is in
 * flight, mirroring the original fragment's single in-flight refresh guard.
 */
data class AppListUiState(
    val apps: List<AppInfo> = emptyList(),
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val busy: Boolean = false,
    val searchQuery: String = "",
    val error: AppListError? = null,
    // Cross-profile (work-profile-only) caches, mirroring the original fragment's sets.
    val crossProfileWidgetProviders: Set<String> = emptySet(),
    val crossProfilePackages: Set<String> = emptySet(),
    // Multi-select (work-profile only).
    val multiSelectMode: Boolean = false,
    val selected: Set<String> = emptySet(),
) {
    /** Apps matching the current search filter (label or package), original fragment semantics. */
    val filteredApps: List<AppInfo>
        get() {
            val query = searchQuery.trim()
            if (query.isEmpty()) return apps
            return apps.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.packageName.contains(query, ignoreCase = true)
            }
        }

    val selectedApps: List<AppInfo>
        get() = apps.filter { it.packageName in selected }
}
