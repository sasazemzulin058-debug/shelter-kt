package net.typeblog.shelter.ui.main

import android.content.ComponentName
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.typeblog.shelter.R
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.DummyActivity
import net.typeblog.shelter.services.IShelterService
import net.typeblog.shelter.ui.settings.SettingsScreen
import net.typeblog.shelter.ui.settings.SettingsViewModel

/**
 * Root Compose screen for the main activity.
 *
 * Shows main/work tabs, a shared search query, the global actions that used to
 * live in the options menu (refresh, show-all, freeze-all, APK install,
 * documents UI, settings), and hosts the settings sub-screen inline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    serviceMain: IShelterService?,
    serviceWork: IShelterService?,
    onInstallApk: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 2 })
    var showSettings by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    var showAllWarning by remember { mutableStateOf(false) }

    val settingsVm: SettingsViewModel = hiltViewModel()
    LaunchedEffect(serviceWork) { settingsVm.setWorkService(serviceWork) }

    // Two independent app-list view models, keyed by profile.
    val mainVm = hiltViewModel<AppListViewModel>(key = "main")
    val workVm = hiltViewModel<AppListViewModel>(key = "work")
    DisposableEffect(Unit) {
        mainVm.isRemote = false
        workVm.isRemote = true
        onDispose {}
    }

    LaunchedEffect(searchQuery) {
        mainVm.setSearchQuery(searchQuery)
        workVm.setSearchQuery(searchQuery)
    }
    LaunchedEffect(showAll) {
        mainVm.refresh(showAll)
        workVm.refresh(showAll)
    }

    // Keep tabs and pager in sync.
    LaunchedEffect(selectedTab) { pagerState.animateScrollToPage(selectedTab) }
    LaunchedEffect(pagerState.currentPage) { selectedTab = pagerState.currentPage }

    // Observe multi-select state so we can swap the top bar into action mode.
    val mainState by mainVm.state.collectAsStateWithLifecycle()
    val workState by workVm.state.collectAsStateWithLifecycle()
    val inActionMode = (selectedTab == 0 && mainState.multiSelectMode) ||
        (selectedTab == 1 && workState.multiSelectMode)

    if (showSettings) {
        SettingsScreen()
        BackHandler { showSettings = false }
        return
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    if (searchActive) {
                        TextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text(stringResource(R.string.search)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text(stringResource(R.string.app_name))
                    }
                },
                navigationIcon = {
                    if (inActionMode) {
                        val vm = if (selectedTab == 0) mainVm else workVm
                        IconButton(onClick = { vm.setMultiSelectMode(false) }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                },
                actions = {
                    if (!inActionMode) {
                        IconButton(onClick = { searchActive = !searchActive }) {
                            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.search))
                        }
                        IconButton(onClick = { scope.launch { mainVm.refresh(); workVm.refresh() } }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.search))
                        }
                        IconButton(onClick = {
                            if (!showAll) {
                                showAllWarning = true
                            } else {
                                showAll = false
                            }
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_lock_open_white_24dp),
                                contentDescription = stringResource(R.string.show_all),
                            )
                        }
                        IconButton(onClick = {
                            context.startActivity(
                                Intent(Actions.PUBLIC_FREEZE_ALL).apply {
                                    component = ComponentName(context, DummyActivity::class.java)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                            )
                        }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_notification_white_24dp),
                                contentDescription = stringResource(R.string.freeze_all),
                            )
                        }
                        IconButton(onClick = onInstallApk) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.install_app_to_profile))
                        }
                        IconButton(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW).setDataAndType(null, "vnd.android.document/root")
                            )
                        }) {
                            Icon(Icons.Default.Folder, contentDescription = stringResource(R.string.documents_ui))
                        }
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(stringResource(R.string.tab_main)) },
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(stringResource(R.string.tab_work)) },
                )
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val isRemote = page == 1
                AppListScreen(
                    isRemote = isRemote,
                    showAll = showAll,
                    searchQuery = searchQuery,
                )
            }
        }
    }

    if (showAllWarning) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showAllWarning = false },
            title = { Text(stringResource(R.string.show_all)) },
            text = { Text(stringResource(R.string.show_all_warning)) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showAll = true
                    showAllWarning = false
                }) {
                    Text(stringResource(R.string.first_run_alert_continue))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showAllWarning = false }) {
                    Text(stringResource(R.string.first_run_alert_cancel))
                }
            },
        )
    }
}
