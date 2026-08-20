package net.typeblog.shelter.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.RemoteException
import android.provider.Settings
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.AuthManager
import net.typeblog.shelter.profile.ProfileManager
import net.typeblog.shelter.services.IShelterService
import net.typeblog.shelter.util.Utility

/**
 * Immutable snapshot of every settings field plus device capability flags.
 *
 * [fileShuttleSupported] mirrors the original Screen gate: File Shuttle is
 * disabled on Android 10 (Q) and on low-RAM ("Go") devices because it needs
 * SYSTEM_ALERT_WINDOW which is not allowed there.
 */
data class SettingsUiState(
    val crossProfileFileChooser: Boolean = false,
    val fileShuttleSupported: Boolean = true,
    val blockContactsSearching: Boolean = false,
    val autoFreezeService: Boolean = false,
    val autoFreezeDelaySeconds: Int = 0,
    val skipForeground: Boolean = false,
    val paymentStub: Boolean = false,
)

/** A permission dialog to show before a gated toggle can be enabled. */
data class PermissionRequest(
    val message: String,
    val settingsAction: String,
)

/**
 * Backs the SettingsScreen. Mirrors the original `SettingsManager` /
 * `SettingsFragment` behavior exactly:
 *
 *  - Cross-profile file chooser, block-contacts, auto-freeze delay and
 *    skip-foreground are synchronized to the work profile via the
 *    SYNCHRONIZE_PREFERENCE action (mirrors `syncSettingsToProfile*`).
 *  - Auto-freeze service is process-local only (never synced).
 *  - Payment stub and the file-chooser component are applied in this profile
 *    only through [ProfileManager.applyProfileSettings].
 *  - The work-profile ShelterService binder is supplied by MainActivity via
 *    [setWorkService] and used only to confirm special-access permissions on
 *    the work side before enabling a gated toggle.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore,
    private val auth: AuthManager,
) : ViewModel() {

    /** Device-static capability flags, computed once at construction. */
    private val fileShuttleSupported: Boolean = run {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        Build.VERSION.SDK_INT != Build.VERSION_CODES.Q && !manager.isLowRamDevice
    }

    /** Work-profile ShelterService binder, supplied by MainActivity. */
    private val workServiceFlow = MutableStateFlow<IShelterService?>(null)

    /** Emits a permission dialog request when a gated toggle cannot be enabled yet. */
    private val _permissionRequests = MutableSharedFlow<PermissionRequest>(extraBufferCapacity = 1)
    val permissionRequests = _permissionRequests.asSharedFlow()

    val uiState: StateFlow<SettingsUiState> = combine(
        combine(
            settings.observe(SettingsStore.Keys.CROSS_PROFILE_FILE_CHOOSER, false),
            settings.observe(SettingsStore.Keys.BLOCK_CONTACTS_SEARCHING, false),
            settings.observe(SettingsStore.Keys.AUTO_FREEZE_SERVICE, false),
        ) { chooser, contacts, auto -> Triple(chooser, contacts, auto) },
        combine(
            settings.observe(SettingsStore.Keys.AUTO_FREEZE_DELAY, 0L),
            settings.observe(SettingsStore.Keys.DONT_FREEZE_FOREGROUND, false),
            settings.observe(SettingsStore.Keys.PAYMENT_STUB, false),
        ) { delay, skip, stub -> Triple(delay, skip, stub) },
    ) { interaction, services ->
        SettingsUiState(
            crossProfileFileChooser = interaction.first,
            fileShuttleSupported = fileShuttleSupported,
            blockContactsSearching = interaction.second,
            autoFreezeService = interaction.third,
            autoFreezeDelaySeconds = services.first.toInt(),
            skipForeground = services.second,
            paymentStub = services.third,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState(),
    )

    /** Called by MainActivity once the work-profile ShelterService binder arrives. */
    fun setWorkService(service: IShelterService?) {
        workServiceFlow.value = service
    }

    // ------------------------------------------------------------------ toggles

    fun setCrossProfileFileChooser(enabled: Boolean) {
        if (!enabled) {
            setAndSyncBool(SettingsStore.Keys.CROSS_PROFILE_FILE_CHOOSER, "cross_profile_file_chooser", false)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!checkFileAccess()) {
                emitPermission(
                    "Shelter needs access to All Files for File Shuttle. Please enable the permission for both the "
                        + "(Personal / Work) Shelter apps shown in the dialog after you press OK.",
                    Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                )
                return
            }
            if (!checkSystemAlert()) {
                emitPermission(
                    "Shelter needs to Draw over Other Apps for File Shuttle to function. Please enable the permission "
                        + "for both the (Personal / Work) Shelter apps shown in the dialog. It is used to start File "
                        + "Shuttle services in the background.",
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                )
                return
            }
        }
        setAndSyncBool(SettingsStore.Keys.CROSS_PROFILE_FILE_CHOOSER, "cross_profile_file_chooser", true)
    }

    fun setBlockContactsSearching(enabled: Boolean) {
        settings.syncSetBoolean(SettingsStore.Keys.BLOCK_CONTACTS_SEARCHING, enabled)
        // The work profile refreshes its contacts-search policy on receipt.
        syncToProfile("block_contacts_searching", boolean = enabled)
    }

    /** Process-local only; never synchronized across the profile boundary. */
    fun setAutoFreezeService(enabled: Boolean) {
        settings.syncSetBoolean(SettingsStore.Keys.AUTO_FREEZE_SERVICE, enabled)
    }

    fun setAutoFreezeDelay(seconds: Int) {
        settings.syncSetLong(SettingsStore.Keys.AUTO_FREEZE_DELAY, seconds.toLong())
        syncToProfile("auto_freeze_delay", int = seconds)
    }

    fun setSkipForeground(enabled: Boolean) {
        if (!enabled) {
            setAndSyncBool(SettingsStore.Keys.DONT_FREEZE_FOREGROUND, "dont_freeze_foreground", false)
            return
        }
        if (!checkUsageStats()) {
            emitPermission(
                "Shelter needs the Usage Stats permission to do this. Please enable it for both Shelter apps shown in "
                    + "the dialog after you press OK, otherwise this feature will not work properly.",
                Settings.ACTION_USAGE_ACCESS_SETTINGS,
            )
            return
        }
        setAndSyncBool(SettingsStore.Keys.DONT_FREEZE_FOREGROUND, "dont_freeze_foreground", true)
    }

    /** Applied in this profile only; the payment stub never crosses profiles. */
    fun setPaymentStub(enabled: Boolean) {
        settings.syncSetBoolean(SettingsStore.Keys.PAYMENT_STUB, enabled)
        ProfileManager.applyProfileSettings(context, settings)
    }

    // ------------------------------------------------------------------ helpers

    private fun setAndSyncBool(key: Preferences.Key<Boolean>, name: String, value: Boolean) {
        settings.syncSetBoolean(key, value)
        ProfileManager.applyProfileSettings(context, settings)
        syncToProfile(name, boolean = value)
    }

    private fun syncToProfile(name: String, boolean: Boolean? = null, int: Int? = null) {
        val intent = Intent(Actions.SYNCHRONIZE_PREFERENCE).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra("name", name)
            if (boolean != null) putExtra("boolean", boolean)
            if (int != null) putExtra("int", int)
        }
        try {
            ProfileManager.transferIntentToProfile(context, intent, auth)
            context.startActivity(intent)
        } catch (_: Exception) {
            // No counterpart profile: the preference stays local.
        }
    }

    private fun emitPermission(message: String, action: String) {
        _permissionRequests.tryEmit(PermissionRequest(message, action))
    }

    // Permission checks require confirmation on BOTH profiles; the work side is
    // confirmed through the work ShelterService, the local side via Utility.
    // A missing or crashed work binder falls back to the local-only check so
    // the toggle stays usable before MainActivity completes its binding.

    private fun checkUsageStats(): Boolean =
        Utility.checkUsageStatsPermission(context) && workCheck { it.hasUsageStatsPermission() }

    private fun checkSystemAlert(): Boolean =
        Utility.checkSystemAlertPermission(context) && workCheck { it.hasSystemAlertPermission() }

    private fun checkFileAccess(): Boolean =
        Utility.checkAllFileAccessPermission() && workCheck { it.hasAllFileAccessPermission() }

    private inline fun workCheck(check: (IShelterService) -> Boolean): Boolean {
        val service = workServiceFlow.value ?: return true
        return try {
            check(service)
        } catch (_: RemoteException) {
            false
        }
    }
}
