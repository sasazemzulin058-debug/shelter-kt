package net.typeblog.shelter.profile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.receivers.DeviceAdminReceiver
import net.typeblog.shelter.services.PaymentStubService
import net.typeblog.shelter.ui.MainActivity
import net.typeblog.shelter.util.CrossProfileDocumentsProvider

/**
 * Profile control-plane: profile-owner detection, cross-profile intent
 * transfer, the work-profile policy direction matrix, user restrictions and
 * settings-driven component toggling.
 *
 * This is the Kotlin equivalent of the original `Utility` profile helpers plus
 * `SettingsManager.applyAll()` component toggling. It is stateless; any
 * dependencies (AuthManager, SettingsStore) are passed in so the same Hilt
 * singleton AuthManager is used everywhere.
 */
object ProfileManager {

    /** True when this process runs as the device/profile owner of the current user. */
    fun isProfileOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isProfileOwnerApp(context.packageName)

    private fun adminComponent(context: Context): ComponentName =
        ComponentName(context.applicationContext, DeviceAdminReceiver::class.java)

    /**
     * Resolve [intent] to the Shelter component living in the *other* profile.
     * The caller must populate the intent's action first. Throws
     * [IllegalStateException] when no counterpart profile exists.
     *
     * Resolution is fail-closed: EVERY component matching [intent] must be this
     * app's exact [DummyActivity] class inside this app's package. Any other
     * handler — an overlay, a clone, or an app abusing the action — makes the
     * counterpart ambiguous/untrusted and is rejected outright; it is never
     * silently skipped in favor of the Shelter match, because the counterpart
     * profile is only trustworthy when nothing else can claim the intent there.
     * Failure modes:
     *  - no resolver -> no counterpart profile;
     *  - any foreign or unexpected component -> reject.
     */
    private fun resolveCounterpart(context: Context, intent: Intent): ComponentName {
        val resolved: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
        if (resolved.isEmpty()) {
            throw IllegalStateException("Cannot find a Shelter counterpart component")
        }
        for (ri in resolved) {
            if (ri.activityInfo.packageName != context.packageName ||
                ri.activityInfo.name != DummyActivity::class.java.name
            ) {
                throw IllegalStateException(
                    "Counterpart intent resolves to non-Shelter component " +
                        "${ri.activityInfo.packageName}/${ri.activityInfo.name}; refusing"
                )
            }
        }
        // All resolvers are the same Shelter activity; any member describes it.
        val info = resolved[0]
        return ComponentName(info.activityInfo.packageName, info.activityInfo.name)
    }

    /**
     * Repoint [intent] at the counterpart profile's Shelter activity without
     * signing it. Used for probing and for intents that require no signature.
     */
    fun transferIntentToProfileUnsigned(context: Context, intent: Intent) {
        intent.component = resolveCounterpart(context, intent)
    }

    /** Repoint [intent] at the counterpart profile and sign it with [auth]. */
    fun transferIntentToProfile(context: Context, intent: Intent, auth: AuthManager) {
        transferIntentToProfileUnsigned(context, intent)
        auth.signIntent(intent)
    }

    /**
     * Probe whether the managed profile is reachable. On success marks the
     * local setup flags complete; on failure returns false (and leaves state
     * for the caller to reconstruct). Mirrors the original non-signed probe.
     */
    fun isWorkProfileAvailable(context: Context, settings: SettingsStore): Boolean {
        val intent = Intent(Actions.TRY_START_SERVICE)
        return try {
            // Deliberately unsigned: this probe is never delivered cross-profile,
            // and signing it first would consume the TOFU bootstrap grant.
            transferIntentToProfileUnsigned(context, intent)
            settings.syncSetBooleanByName("is_setting_up", false)
            settings.syncSetBooleanByName("has_setup", true)
            true
        } catch (_: IllegalStateException) {
            false
        }
    }

    // ------------------------------------------------------------------ policy

    private fun crossProfileFilter(action: String): IntentFilter = IntentFilter(action)

    /**
     * Enforce the work-profile cross-profile intent direction matrix exactly as
     * the original, plus hidden-launcher and profile-enabled guarantees.
     * Caller passes [settings] only to read the contacts option.
     */
    fun enforceWorkProfilePolicies(context: Context, settings: SettingsStore) {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val admin = adminComponent(context)

        // Hide this app's launcher inside the work profile.
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.applicationContext, MainActivity::class.java),
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            0,
        )

        // Clear everything first so our policies are set freshly.
        manager.clearCrossProfileIntentFilters(admin)

        data class FilterSpec(val filter: IntentFilter, val flags: Int)

        val specs = listOf(
            FilterSpec(crossProfileFilter(Actions.START_SERVICE), DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT),
            FilterSpec(crossProfileFilter(Actions.TRY_START_SERVICE), DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT),
            FilterSpec(crossProfileFilter(Actions.UNFREEZE_AND_LAUNCH), DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT),
            FilterSpec(crossProfileFilter(Actions.FREEZE_ALL_IN_LIST), DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT),
            // Used by FreezeService in the profile.
            FilterSpec(crossProfileFilter(Actions.PUBLIC_FREEZE_ALL), DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED),
            FilterSpec(crossProfileFilter(Actions.FINALIZE_PROVISION), DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED),
            FilterSpec(crossProfileFilter(Actions.START_FILE_SHUTTLE), DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT),
            FilterSpec(crossProfileFilter(Actions.START_FILE_SHUTTLE_2), DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED),
            FilterSpec(crossProfileFilter(Actions.SYNCHRONIZE_PREFERENCE), DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT),
            // Needed by ShelterService, proxied by MainActivity in the parent profile.
            FilterSpec(crossProfileFilter(Actions.INSTALL_PACKAGE), DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT),
            FilterSpec(crossProfileFilter(Actions.UNINSTALL_PACKAGE), DevicePolicyManager.FLAG_MANAGED_CAN_ACCESS_PARENT),
        )
        for (spec in specs) {
            manager.addCrossProfileIntentFilter(admin, spec.filter, spec.flags)
        }

        // ACTION_SEND / ACTION_SEND_MULTIPLE cross from managed to parent.
        val sendFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SEND)
            addAction(Intent.ACTION_SEND_MULTIPLE)
            try {
                addDataType("*/*")
            } catch (_: IntentFilter.MalformedMimeTypeException) {
                // Ignore: "*/*" is well-formed on every supported platform.
            }
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        manager.addCrossProfileIntentFilter(
            admin, sendFilter, DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)

        // Browsable http/https intents cross from managed to parent.
        fun browsableFilter(withDefault: Boolean): IntentFilter = IntentFilter(Intent.ACTION_VIEW).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            if (withDefault) addCategory(Intent.CATEGORY_DEFAULT)
            addDataScheme("http")
            addDataScheme("https")
        }
        manager.addCrossProfileIntentFilter(
            admin, browsableFilter(false), DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)
        manager.addCrossProfileIntentFilter(
            admin, browsableFilter(true), DevicePolicyManager.FLAG_PARENT_CAN_ACCESS_MANAGED)

        manager.setCrossProfileContactsSearchDisabled(
            admin, settings.syncGetBoolean("block_contacts_searching"))

        manager.setProfileEnabled(admin)
    }

    fun enforceUserRestrictions(context: Context) {
        val manager = context.getSystemService(DevicePolicyManager::class.java)
        val admin = adminComponent(context)

        manager.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
        manager.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
        manager.clearUserRestriction(admin, UserManager.DISALLOW_UNINSTALL_APPS)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // Polyfill for DISALLOW_INSTALL_UNKNOWN_SOURCES; crashes on O+.
            manager.setSecureSetting(admin, Settings.Secure.INSTALL_NON_MARKET_APPS, "1")
        }

        manager.addUserRestriction(admin, UserManager.ALLOW_PARENT_PROFILE_APP_LINKING)
    }

    /**
     * Enable/disable settings-driven components (cross-profile file chooser,
     * payment stub) after a synchronization or startup. Equivalent of the
     * original `SettingsManager.applyAll()` component toggling.
     */
    fun applyProfileSettings(context: Context, settings: SettingsStore) {
        val pm = context.packageManager

        fun setComponent(enabled: Boolean, component: ComponentName) {
            val state = if (enabled) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
        }

        setComponent(
            settings.syncGetBoolean("cross_profile_file_chooser"),
            ComponentName(context.applicationContext, CrossProfileDocumentsProvider::class.java),
        )
        setComponent(
            settings.syncGetBoolean("payment_stub"),
            ComponentName(context.applicationContext, PaymentStubService::class.java),
        )
    }
}
