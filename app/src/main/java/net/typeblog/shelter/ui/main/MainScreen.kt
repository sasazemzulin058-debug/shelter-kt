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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 2 })
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showAll by rememberSaveable { mutableStateOf(false) }
    var showAllWarning by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }

    val settingsVm: SettingsViewModel = hiltViewModel()
    LaunchedEffect(serviceWork) { settingsVm.setWorkService(serviceWork) }

    // Two independent app-list view models, keyed by profile.
    val mainVm = hiltViewModel<AppListViewModel>(key = "main")
    val workVm = hiltViewModel<AppListViewModel>(key = "work")
    DisposableEffect(Unit) {
        mainVm.isRemote = false
        workVm.isRemote = true
        mainVm.setSiblingViewModel(workVm)
        workVm.setSiblingViewModel(mainVm)
        onDispose {}
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                mainVm.refresh()
                workVm.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Bind each list to its profile's service binder (plus the sibling binder for
    // clone-to-other-profile) before any refresh runs.
    LaunchedEffect(serviceMain, serviceWork) {
        if (serviceMain != null) mainVm.configureServices(serviceMain, serviceWork)
        if (serviceWork != null) workVm.configureServices(serviceWork, serviceMain)
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
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.first_run_alert_cancel))
                        }
                    }
                },
                actions = {
                    if (!inActionMode) {
                        IconButton(onClick = {
                            if (searchActive) {
                                searchQuery = ""
                            }
                            searchActive = !searchActive
                        }) {
                            Icon(
                                if (searchActive) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = stringResource(if (searchActive) R.string.first_run_alert_cancel else R.string.search),
                            )
                        }
                        IconButton(onClick = { mainVm.refresh(); workVm.refresh() }) {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.settings_services))
                        }
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.show_all)) },
                                onClick = {
                                    menuExpanded = false
                                    if (!showAll) {
                                        showAllWarning = true
                                    } else {
                                        showAll = false
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.freeze_all)) },
                                onClick = {
                                    menuExpanded = false
                                    context.startActivity(
                                        Intent(Actions.PUBLIC_FREEZE_ALL).apply {
                                            component = ComponentName(context, DummyActivity::class.java)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.create_freeze_all_shortcut)) },
                                onClick = {
                                    menuExpanded = false
                                    val launchIntent = Intent(Actions.PUBLIC_FREEZE_ALL).apply {
                                        component = ComponentName(context, DummyActivity::class.java)
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    }
                                    net.typeblog.shelter.util.Utility.createLauncherShortcut(
                                        context,
                                        launchIntent,
                                        android.graphics.drawable.Icon.createWithResource(context, R.mipmap.ic_freeze),
                                        "shelter-freeze-all",
                                        context.getString(R.string.freeze_all_shortcut),
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.install_app_to_profile)) },
                                onClick = {
                                    menuExpanded = false
                                    onInstallApk()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.documents_ui)) },
                                onClick = {
                                    menuExpanded = false
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW).setDataAndType(null, "vnd.android.document/root")
                                    )
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = {
                                    menuExpanded = false
                                    showSettings = true
                                },
                            )
                        }
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
