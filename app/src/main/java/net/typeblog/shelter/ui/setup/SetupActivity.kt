package net.typeblog.shelter.ui.setup

import android.app.admin.DevicePolicyManager
import android.os.PersistableBundle
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
    }

    @Inject lateinit var settings: SettingsStore
    @Inject lateinit var authManager: AuthManager

    private val provisionProfile: ActivityResultLauncher<Unit> =
        registerForActivityResult(ProfileProvisionContract()) { result ->
            setupProfileCb(result)
        }

    private var step by mutableStateOf(Step.WELCOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // The user may tap the "finish provisioning" notification while this
        // activity was removed from recents, which starts a fresh instance.
        if (ACTION_PROFILE_PROVISIONED == intent.action && ProfileManager.isWorkProfileAvailable(this, settings)) {
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
                    if (step == Step.PROVISIONING || step == Step.FAILED) {
                        step = Step.PROVISIONING
                        setupProfile()
                    }
                },
                onFinish = { finalizeProvisionManually() },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_STEP, step.name)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Finalization has already been authenticated by DummyActivity before
        // this explicit callback reaches the setup activity.
        if (ACTION_PROFILE_PROVISIONED == intent.action) {
            finishWithResult(true)
        }
    }
    private fun finishWithResult(succeeded: Boolean) {
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
        } catch (_: IllegalStateException) {
            step = Step.ACTION_REQUIRED
        }
    }

    private fun setupProfile() {
        val policy = getSystemService(DevicePolicyManager::class.java)
        if (!policy.isProvisioningAllowed(DevicePolicyManager.ACTION_PROVISION_MANAGED_PROFILE)) {
            step = Step.FAILED
            return
        }
        // The user may have aborted provisioning before without clearing data,
        // leaving stale auth secrets that would make us believe we can authenticate.
        authManager.reset()
        try {
            provisionProfile.launch(Unit)
        } catch (_: ActivityNotFoundException) {
            step = Step.FAILED
        }
    }

    private fun setupProfileCb(result: Boolean) {
        if (result) {
            // On Oreo+, ACTION_PROVISIONING_SUCCESSFUL is delivered only after
            // FinalizeActivity has run. Do not probe the resolver here: Android
            // may publish it slightly later, and the original contract waits on
            // this callback itself.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                finishWithResult(true)
                return
            }
            if (ProfileManager.isWorkProfileAvailable(this, settings)) {
                finishWithResult(true)
                return
            }
            settings.syncSetBoolean(SettingsStore.Keys.IS_SETTING_UP, true)
            step = Step.ACTION_REQUIRED
        } else {
            authManager.reset()
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
