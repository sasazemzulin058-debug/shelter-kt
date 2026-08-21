package net.typeblog.shelter.profile

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
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
     * Resolve [intent] to the platform component that delivers it into the
     * *other* profile. The caller must populate the intent's action first.
     * Throws [IllegalStateException] when no counterpart profile exists or
     * when the resolution cannot be trusted.
     *
     * Cross-profile delivery is realized by the platform's cross-profile
     * intent forwarder. When this app's profile owner registered a matching
     * cross-profile intent filter ([enforceWorkProfilePolicies]), activity
     * resolution in the *source* profile returns the local shell component
     * plus the platform forwarder entry
     * ([ResolveInfo.isCrossProfileIntentForwarderActivity]); only starting
     * that forwarder preserves the target-user identity. Pinning the intent to
     * this package's own [DummyActivity] would keep the delivery in the
     * current user and bypass cross-user routing, so that component is never
     * selected here.
     *
     * Selection is deterministic and fail-closed:
     *  - the platform forwarder entry (confirmed by
     *    [ResolveInfo.isCrossProfileIntentForwarderActivity] and a
     *    system-application check) is the ONLY foreign component accepted, and
     *    is preferred whenever present;
     *  - this package's exact [DummyActivity] is an accepted context match but
     *    is never chosen as the routing target;
     *  - any other handler — a third-party app declaring the action, a clone,
     *    an overlay — makes the resolution ambiguous/untrusted and the whole
     *    transfer is rejected. It is never skipped in favor of the forwarder:
     *    an attacker who can interpose on the cross-profile path would
     *    otherwise receive signed payloads and the bootstrap key.
     *
     * Device prerequisite: AOSP/SAF-style managed-profile devices where a
     * cross-profile intent filter registered by this app's profile owner
     * surfaces the platform forwarder in
     * [android.content.pm.PackageManager.queryIntentActivities]. On devices
     * whose platform does not surface that forwarder entry for the action, the
     * transfer fails closed with [IllegalStateException] instead of guessing a
     * component; callers then report "no work profile available" and trigger a
     * re-setup, which re-registers the filters.
     *
     * Failure modes:
     *  - no resolvers -> no counterpart profile or filters not yet registered;
     *  - only the local [DummyActivity] matches -> counterpart unreachable
     *    (no profile or filters cleared); equivalent to the original app's
     *    "Cannot find an intent in other profile";
     *  - a non-system handler matches -> ambiguous/untrusted, refused.
     */
    private fun resolveCounterpart(context: Context, intent: Intent): ComponentName {
        val resolved: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
        if (resolved.isEmpty()) {
            throw IllegalStateException("Cannot find a Shelter counterpart component")
        }
        val ownPackage = context.packageName
        val dummyClass = DummyActivity::class.java.name
        var localDummy = false
        var forwarder: ComponentName? = null
        for (ri in resolved) {
            val ai = ri.activityInfo ?: throw IllegalStateException(
                "Counterpart intent resolves to a component without activity info; refusing")
            if (ai.packageName == ownPackage && ai.name == dummyClass) {
                localDummy = true
                continue
            }
            if (ri.isCrossProfileIntentForwarderActivity && isSystemApplication(ai)) {
                val candidate = ComponentName(ai.packageName, ai.name)
                if (forwarder != null && forwarder != candidate) {
                    throw IllegalStateException(
                        "Multiple platform cross-profile forwarders resolve " +
                            "${intent.action}; refusing ambiguous routing"
                    )
                }
                forwarder = candidate
                continue
            }
            throw IllegalStateException(
                "Counterpart intent resolves to unverified component " +
                    "${ai.packageName}/${ai.name}; refusing ambiguous routing"
            )
        }
        // The platform forwarder is the only component that preserves the
        // target-user identity; prefer it over the local context match.
        if (forwarder != null) {
            return forwarder
        }
        // Only our own activity matched: the counterpart profile is absent or
        // its cross-profile filters were cleared.
        if (localDummy) {
            throw IllegalStateException("Cannot find a Shelter counterpart component")
        }
        throw IllegalStateException("Cannot find a Shelter counterpart component")
    }

    /** True when [ai] belongs to a system application (the platform itself). */
    @Suppress("DEPRECATION") // FLAG_SYSTEM is the only system-app signal below API 29.
    private fun isSystemApplication(ai: ActivityInfo): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        } else {
            (ai.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        }

    /**
     * Repoint [intent] at the platform component that routes it into the
     * counterpart profile, without signing it. Used for probing and for
     * intents that require no signature.
     */
    fun transferIntentToProfileUnsigned(context: Context, intent: Intent) {
        intent.component = resolveCounterpart(context, intent)
    }

    /** Repoint [intent] at the counterpart profile (via the platform
     *  cross-profile forwarder) and sign it with [auth]. */
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
