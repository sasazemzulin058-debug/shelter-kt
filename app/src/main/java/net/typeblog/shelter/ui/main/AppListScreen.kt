package net.typeblog.shelter.ui.main

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.typeblog.shelter.R
import net.typeblog.shelter.data.model.AppInfo
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.DummyActivity
import net.typeblog.shelter.util.Utility

/**
 * Compose screen that mirrors the original [AppListFragment].
 *
 * It renders the filtered app list for one profile, loads icons on demand,
 * exposes the long-press context menu actions, and supports work-profile
 * multi-select batch operations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(
    isRemote: Boolean,
    showAll: Boolean,
    searchQuery: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val vm = hiltViewModel<AppListViewModel>(key = if (isRemote) "work" else "main")
    DisposableEffect(isRemote) {
        vm.isRemote = isRemote
        onDispose {}
    }

    val state by vm.state.collectAsStateWithLifecycle()
    val icons = remember { mutableStateMapOf<String, Bitmap>() }

    LaunchedEffect(searchQuery) { vm.setSearchQuery(searchQuery) }
    LaunchedEffect(showAll) { vm.refresh(showAll) }
    LaunchedEffect(Unit) { if (state.apps.isEmpty()) vm.refresh(showAll) }

    // Surface transient errors.
    LaunchedEffect(state.error) {
        val message = when (state.error) {
            AppListError.ServiceUnavailable -> context.getString(R.string.service_unavailable)
            AppListError.AppNotFound -> context.getString(R.string.no_apps)
            AppListError.Generic -> context.getString(R.string.setup_failed)
            null -> null
        }
        if (message != null) {
            snackbarHostState.showSnackbar(message)
        }
    }

    var menuApp by remember { mutableStateOf<AppInfo?>(null) }
    var miuiApp by remember { mutableStateOf<AppInfo?>(null) }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (state.multiSelectMode) {
                val selectedList = remember(state.selected) { state.selected.toList() }
                TopAppBar(
                    title = {
                        Text(
                            stringResource(R.string.selected_count, selectedList.size),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { vm.setMultiSelectMode(false) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = null,
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                createBatchShortcut(context, vm, state.selectedApps)
                            }
                        }) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.create_unfreeze_shortcut),
                            )
                        }
                        IconButton(onClick = {
                            vm.freezeMany(selectedList)
                            vm.setMultiSelectMode(false)
                        }) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = stringResource(R.string.batch_freeze),
                            )
                        }
                    },
                )
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading && state.apps.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                state.filteredApps.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.no_apps),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                            .align(Alignment.Center),
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = state.filteredApps,
                            key = { it.packageName },
                        ) { app ->
                            val selectedOrder = remember(state.selected) {
                                state.selected.toList().indexOf(app.packageName).takeIf { it >= 0 }?.plus(1)
                            }

                            LaunchedEffect(app.packageName) {
                                if (!icons.containsKey(app.packageName)) {
                                    try {
                                        icons[app.packageName] = vm.loadIcon(app.packageName)
                                    } catch (_: Exception) {
                                        // Keep default icon on failure.
                                    }
                                }
                            }

                            AppItem(
                                app = app,
                                icon = icons[app.packageName],
                                isSelected = app.packageName in state.selected,
                                selectionOrder = selectedOrder,
                                onClick = {
                                    if (state.multiSelectMode) {
                                        vm.toggleSelection(app.packageName)
                                    } else {
                                        menuApp = app
                                    }
                                },
                                onLongClick = {
                                    if (isRemote && !state.multiSelectMode) {
                                        vm.setMultiSelectMode(true)
                                        vm.toggleSelection(app.packageName)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    menuApp?.let { app ->
        AppActionsDialog(
            app = app,
            isRemote = isRemote,
            isHidden = app.isHidden,
            isSystem = app.isSystem,
            isInAutoFreezeList = app.isInAutoFreezeList,
            widgetAllowed = app.packageName in state.crossProfileWidgetProviders,
            interactionAllowed = app.packageName in state.crossProfilePackages,
            onDismiss = { menuApp = null },
            onAction = { action ->
                menuApp = null
                when (action) {
                    AppAction.CLONE -> {
                        if (Utility.isMIUI() && !app.isSystem) {
                            miuiApp = app
                        } else {
                            vm.clone(app.packageName)
                        }
                    }
                    AppAction.UNINSTALL -> vm.uninstall(app.packageName)
                    AppAction.FREEZE -> {
                        vm.freeze(app.packageName) { success ->
                            if (success) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.freeze_success, app.label),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                    AppAction.UNFREEZE -> {
                        vm.unfreeze(app.packageName) { success ->
                            if (success) {
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.unfreeze_success, app.label),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    }
                    AppAction.LAUNCH -> launchApp(context, app.packageName)
                    AppAction.UNFREEZE_AND_LAUNCH -> launchApp(context, app.packageName)
                    AppAction.AUTO_FREEZE -> vm.toggleAutoFreeze(app.packageName)
                    AppAction.CROSS_PROFILE_WIDGET -> vm.setCrossProfileWidget(
                        app.packageName,
                        app.packageName !in state.crossProfileWidgetProviders,
                    )
                    AppAction.CROSS_PROFILE_INTERACTION -> vm.toggleCrossProfileInteraction(app.packageName)
                    AppAction.CREATE_SHORTCUT -> {
                        scope.launch {
                            createShortcut(context, vm, app, null)
                        }
                    }
                }
            },
        )
    }

    miuiApp?.let { app ->
        AlertDialog(
            onDismissRequest = { miuiApp = null },
            title = { Text(stringResource(R.string.clone_to_work_profile)) },
            text = { Text(stringResource(R.string.miui_cannot_clone)) },
            confirmButton = {
                TextButton(onClick = { miuiApp = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    miuiApp = null
                    vm.clone(app.packageName)
                }) {
                    Text(stringResource(R.string.continue_anyway))
                }
            },
        )
    }
}

private enum class AppAction(val labelRes: Int) {
    CLONE(R.string.clone_to_work_profile),
    UNINSTALL(R.string.uninstall_app),
    FREEZE(R.string.freeze_app),
    UNFREEZE(R.string.unfreeze_app),
    LAUNCH(R.string.launch),
    UNFREEZE_AND_LAUNCH(R.string.launch),
    AUTO_FREEZE(R.string.auto_freeze),
    CROSS_PROFILE_WIDGET(R.string.allow_cross_profile_widgets),
    CROSS_PROFILE_INTERACTION(R.string.allow_cross_profile_interaction),
    CREATE_SHORTCUT(R.string.create_unfreeze_shortcut),
}

@Composable
private fun AppActionsDialog(
    app: AppInfo,
    isRemote: Boolean,
    isHidden: Boolean,
    isSystem: Boolean,
    isInAutoFreezeList: Boolean,
    widgetAllowed: Boolean,
    interactionAllowed: Boolean,
    onDismiss: () -> Unit,
    onAction: (AppAction) -> Unit,
) {
    data class Entry(val action: AppAction, val label: String)

    val actions = buildList {
        if (isRemote) {
            if (!isSystem) add(Entry(AppAction.CLONE, stringResource(R.string.clone_to_main_profile)))
            if (isHidden) {
                add(Entry(AppAction.UNFREEZE, stringResource(R.string.unfreeze_app)))
                add(Entry(AppAction.UNFREEZE_AND_LAUNCH, stringResource(R.string.launch)))
            } else {
                add(Entry(AppAction.FREEZE, stringResource(R.string.freeze_app)))
                add(Entry(AppAction.LAUNCH, stringResource(R.string.launch)))
            }
            add(Entry(AppAction.CROSS_PROFILE_WIDGET, buildString {
                append(stringResource(R.string.allow_cross_profile_widgets))
                if (widgetAllowed) append(" ✓")
            }))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(Entry(AppAction.CROSS_PROFILE_INTERACTION, buildString {
                    append(stringResource(R.string.allow_cross_profile_interaction))
                    if (interactionAllowed) append(" ✓")
                }))
            }
            add(Entry(AppAction.AUTO_FREEZE, buildString {
                append(stringResource(R.string.auto_freeze))
                if (isInAutoFreezeList) append(" ✓")
            }))
            add(Entry(AppAction.CREATE_SHORTCUT, stringResource(R.string.create_unfreeze_shortcut)))
        } else {
            add(Entry(AppAction.CLONE, stringResource(R.string.clone_to_work_profile)))
        }
        if (!isSystem) {
            add(Entry(AppAction.UNINSTALL, stringResource(R.string.uninstall_app)))
        }
    }

    if (actions.isEmpty()) {
        onDismiss()
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.app_context_menu_title, app.label)) },
        text = {
            Column {
                actions.forEachIndexed { index, entry ->
                    TextButton(
                        onClick = { onAction(entry.action) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(entry.label, modifier = Modifier.fillMaxWidth())
                    }
                    if (index < actions.lastIndex) {
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

private fun launchApp(context: android.content.Context, packageName: String) {
    val intent = Intent(Actions.UNFREEZE_AND_LAUNCH).apply {
        component = ComponentName(context, DummyActivity::class.java)
        putExtra("packageName", packageName)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    DummyActivity.registerSameProcessRequest(intent)
    context.startActivity(intent)
}

private suspend fun createShortcut(
    context: android.content.Context,
    vm: AppListViewModel,
    app: AppInfo,
    linkedPackages: List<String>?,
) {
    val icon = try {
        vm.loadIcon(app.packageName)
    } catch (_: Exception) {
        null
    } ?: return

    val id = buildString {
        append("shelter-")
        append(app.packageName)
        linkedPackages?.let {
            append(it.joinToString(",").hashCode())
        }
    }
    val intent = Intent(Actions.PUBLIC_UNFREEZE_AND_LAUNCH).apply {
        component = ComponentName(context, DummyActivity::class.java)
        putExtra("packageName", app.packageName)
        if (!linkedPackages.isNullOrEmpty()) {
            putExtra("linkedPackages", linkedPackages.joinToString(","))
        }
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    Utility.createLauncherShortcut(
        context,
        intent,
        Icon.createWithBitmap(icon),
        id,
        app.label,
    )
}

private suspend fun createBatchShortcut(
    context: android.content.Context,
    vm: AppListViewModel,
    selectedApps: List<AppInfo>,
) {
    if (selectedApps.isEmpty()) return
    val mainApp = selectedApps.first()
    val linked = selectedApps.drop(1).map { it.packageName }
    createShortcut(context, vm, mainApp, linked)
}
