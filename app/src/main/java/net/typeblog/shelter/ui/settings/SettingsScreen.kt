package net.typeblog.shelter.ui.settings

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import net.typeblog.shelter.R

private data class AutoFreezeDelayOption(val seconds: Int, val labelRes: Int)

// 0 / 60 / 120 / 300 seconds. The supplied label resources (Immediately / 1
// minute / 5 minutes / 10 minutes) are paired in value order; the 120s and 300s
// labels are a pre-existing resource mismatch. ponytail: rename
// delay_5min/delay_10min or add delay_2min if label accuracy matters.
private val AUTO_FREEZE_DELAY_OPTIONS = listOf(
    AutoFreezeDelayOption(0, R.string.delay_immediate),
    AutoFreezeDelayOption(60, R.string.delay_1min),
    AutoFreezeDelayOption(120, R.string.delay_5min),
    AutoFreezeDelayOption(300, R.string.delay_10min),
)

/**
 * Compose settings screen, hosted by MainActivity. Mirrors the original
 * `SettingsFragment`: interaction toggles (File Shuttle, block contacts,
 * payment stub) and service toggles (auto-freeze, delay, skip foreground),
 * with permission-gated switches that open the platform special-access
 * settings when a required permission is missing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pendingPermission by remember { mutableStateOf<PermissionRequest?>(null) }

    LaunchedEffect(Unit) {
        viewModel.permissionRequests.collect { pendingPermission = it }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings)) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 8.dp),
        ) {
            item {
                SectionHeader("Interaction")
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_cross_profile_file_chooser),
                    checked = uiState.crossProfileFileChooser,
                    enabled = uiState.fileShuttleSupported,
                    onCheckedChange = viewModel::setCrossProfileFileChooser,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_block_contacts),
                    checked = uiState.blockContactsSearching,
                    onCheckedChange = viewModel::setBlockContactsSearching,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_payment_stub),
                    checked = uiState.paymentStub,
                    onCheckedChange = viewModel::setPaymentStub,
                )
            }
            item { HorizontalDivider() }
            item {
                SectionHeader("Services")
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_auto_freeze),
                    checked = uiState.autoFreezeService,
                    onCheckedChange = viewModel::setAutoFreezeService,
                )
                AutoFreezeDelayRow(
                    title = stringResource(R.string.settings_freeze_delay),
                    selectedSeconds = uiState.autoFreezeDelaySeconds,
                    onSelect = viewModel::setAutoFreezeDelay,
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.settings_skip_foreground),
                    checked = uiState.skipForeground,
                    onCheckedChange = viewModel::setSkipForeground,
                )
            }
        }
    }

    pendingPermission?.let { req ->
        AlertDialog(
            onDismissRequest = { pendingPermission = null },
            title = { Text(stringResource(R.string.settings)) },
            text = { Text(req.message) },
            confirmButton = {
                TextButton(onClick = {
                    pendingPermission = null
                    context.startActivity(
                        Intent(req.settingsAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPermission = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun AutoFreezeDelayRow(
    title: String,
    selectedSeconds: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = true }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Box {
                Text(
                    text = stringResource(
                        AUTO_FREEZE_DELAY_OPTIONS
                            .firstOrNull { it.seconds == selectedSeconds }?.labelRes
                            ?: R.string.delay_immediate,
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AUTO_FREEZE_DELAY_OPTIONS.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(stringResource(option.labelRes)) },
                            onClick = {
                                onSelect(option.seconds)
                                expanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}
