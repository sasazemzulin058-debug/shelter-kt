package net.typeblog.shelter.ui.setup

import android.app.admin.DevicePolicyManager
import android.os.PersistableBundle
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import android.os.Bundle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.profile.AuthManager
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.ProfileManager
import net.typeblog.shelter.receivers.DeviceAdminReceiver
import net.typeblog.shelter.ui.MainActivity
import java.security.SecureRandom
import javax.inject.Inject

/**
 * Work-profile provisioning wizard.
 *
 * Kotlin Compose port of the original `SetupWizardActivity`. Preserves the
 * persistent provisioning state machine and the two setup-only actions:
 * [ACTION_RESUME_SETUP] and [ACTION_PROFILE_PROVISIONED]:
 *
 *  * [onNewIntent] / [ACTION_PROFILE_PROVISIONED] handles the case where the
 *    background process finalizes provisioning while this activity was removed
 *    from recents or already running (duplicate-finalization handling).
 *  * [setupProfileCb] persists the still-waiting-for-notification state in
 *    [SettingsStore.Keys.IS_SETTING_UP] on pre-O builds, so provisioning can
 *    resume after process death or activity removal.
 */
@AndroidEntryPoint
class SetupActivity : ComponentActivity() {

    companion object {
        const val ACTION_RESUME_SETUP = "net.typeblog.shelter.RESUME_SETUP"
        const val ACTION_PROFILE_PROVISIONED = "net.typeblog.shelter.PROFILE_PROVISIONED"
        private const val STATE_STEP = "setup_step"
        private const val TAG = "ShelterSetup"
    }
    @Inject lateinit var authManager: AuthManager

    private val provisionProfile: ActivityResultLauncher<Unit> =
        registerForActivityResult(ProfileProvisionContract()) { result ->
            setupProfileCb(result)
        }

    private var step by mutableStateOf(Step.WELCOME)
    @Inject lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate action=${intent.action} savedStep=${savedInstanceState?.getString(STATE_STEP)} profileOwner=${ProfileManager.isProfileOwner(this)}")
        Log.i(TAG, "flags isSettingUp=${settings.syncGetBoolean(SettingsStore.Keys.IS_SETTING_UP)} hasSetup=${settings.syncGetBoolean(SettingsStore.Keys.HAS_SETUP)}")
        if (ACTION_PROFILE_PROVISIONED == intent.action && ProfileManager.isWorkProfileAvailable(this, settings)) {
            Log.i(TAG, "profile-provisioned action accepted; launching MainActivity")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        step =
            if (ACTION_RESUME_SETUP == intent.action) Step.ACTION_REQUIRED
            else savedInstanceState?.getString(STATE_STEP)?.let { Step.byName(it) } ?: Step.WELCOME

        setContent {
            SetupScreen(
                step = step,
                onBack = { step = step.onBack() },
                onNext = {
                    val next = step.onNext()
                    step = next
                    if (next == Step.PROVISIONING) setupProfile()
                },
                onRetry = {
                    if (step == Step.PROVISIONING || step == Step.FAILED || step == Step.RECOVERY) {
                        step = Step.PROVISIONING
                        setupProfile()
                    }
                },
                onFinish = { finalizeProvisionManually() },
                onOpenSettings = { openSystemSettings() },
            )
        }
    }

    private fun openSystemSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "system settings activity not found", error)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_STEP, step.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i(TAG, "onNewIntent action=${intent.action}")
        if (ACTION_PROFILE_PROVISIONED == intent.action) {
            Log.i(TAG, "profile-provisioned callback finishing setup")
            finishWithResult(true)
        }
    }

    private fun finishWithResult(succeeded: Boolean) {
        Log.i(TAG, "finishWithResult succeeded=$succeeded")
        setResult(if (succeeded) RESULT_OK else RESULT_CANCELED)
        finish()
    }

    private fun finalizeProvisionManually() {
        val intent = Intent(Actions.FINALIZE_PROVISION).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ProfileManager.transferIntentToProfile(this, intent, authManager)
            startActivity(intent)
        } catch (error: IllegalStateException) {
            Log.e(TAG, "manual finalization route unavailable", error)
            step = Step.RECOVERY
        }
    }

    private fun setupProfile() {
        val policy = getSystemService(DevicePolicyManager::class.java)
        val allowed = policy.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
        val profileCount = getSystemService(UserManager::class.java).userProfiles.size
        Log.i(TAG, "setupProfile allowed=$allowed api=${android.os.Build.VERSION.SDK_INT} profileCount=$profileCount")
        if (!allowed) {
            Log.e(TAG, "provisioning not allowed")
            step = if (profileCount > 1) Step.RECOVERY else Step.FAILED
            return
        }
        authManager.reset()
        settings.syncSetBoolean(SettingsStore.Keys.IS_SETTING_UP, true)
        try {
            Log.i(TAG, "launch provisioning intent")
            provisionProfile.launch(Unit)
        } catch (error: ActivityNotFoundException) {
            Log.e(TAG, "provisioning activity not found", error)
            settings.syncSetBoolean(SettingsStore.Keys.IS_SETTING_UP, false)
            step = Step.FAILED
        }
    }

    private fun setupProfileCb(result: Boolean) {
        Log.i(TAG, "provision result=$result api=${android.os.Build.VERSION.SDK_INT}")
        if (result) {
            Log.i(TAG, "provision success; profileOwner=${ProfileManager.isProfileOwner(this)}")
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                Log.i(TAG, "O+ result; waiting for ACTION_PROFILE_PROVISIONED")
                step = Step.PROVISIONING
                return
            }
            val available = ProfileManager.isWorkProfileAvailable(this, settings)
            Log.i(TAG, "pre-O workProfileAvailable=$available")
            if (available) {
                finishWithResult(true)
                return
            }
            settings.syncSetBoolean(SettingsStore.Keys.IS_SETTING_UP, true)
            Log.w(TAG, "pre-O profile requires action notification")
            step = Step.ACTION_REQUIRED
        } else {
            Log.e(TAG, "provision cancelled or failed")
            authManager.reset()
            settings.syncSetBoolean(SettingsStore.Keys.IS_SETTING_UP, false)
            step = Step.FAILED
        }
    }

    private inner class ProfileProvisionContract : ActivityResultContract<Unit, Boolean>() {
        override fun createIntent(context: Context, input: Unit): Intent {
            val admin = ComponentName(context.applicationContext, DeviceAdminReceiver::class.java)
            // Generate ONE shared 256-bit secret and install it in this (parent)
            // profile NOW. The identical bytes ride the platform provisioning
            // admin extras bundle into the managed profile, where the trusted
            // admin entry points install them via AuthManager.installProvisionedSecret.
            // can seed the shared secret.
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            authManager.installProvisionedSecret(secret)
            val adminExtras = PersistableBundle().apply {
                putString(
                    AuthManager.EXTRA_PROVISIONED_SECRET,
                    android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP),
                )
            }
            return Intent(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)
                .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_SKIP_ENCRYPTION, true)
                .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME, admin)
                .putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, adminExtras)
        }

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            resultCode == RESULT_OK
    }
}
