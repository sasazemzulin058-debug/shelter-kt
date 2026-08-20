package net.typeblog.shelter.ui

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import dagger.hilt.android.AndroidEntryPoint
import net.typeblog.shelter.R
import net.typeblog.shelter.ShelterApp
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.DummyActivity
import net.typeblog.shelter.profile.ProfileManager
import net.typeblog.shelter.services.IAppInstallCallback
import net.typeblog.shelter.services.IShelterService
import net.typeblog.shelter.services.IStartActivityProxy
import net.typeblog.shelter.services.KillerService
import net.typeblog.shelter.ui.main.MainScreen
import net.typeblog.shelter.ui.setup.SetupActivity
import net.typeblog.shelter.ui.theme.ShelterTheme
import net.typeblog.shelter.util.UriForwardProxy
import net.typeblog.shelter.util.Utility
import javax.inject.Inject

/**
 * Main launcher activity.
 *
 * Port of the original Java MainActivity to Compose. It owns the cross-profile
 * service binding lifecycle (main + work), the try-start / start-service
 * handshake, the [IStartActivityProxy] registration, [KillerService] startup,
 * and the ready/error/setup routing. The Compose UI is shown only after both
 * service binders are alive.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settings: SettingsStore

    private val serviceMain = mutableStateOf<IShelterService?>(null)
    private val serviceWork = mutableStateOf<IShelterService?>(null)
    private val isReady = mutableStateOf(false)

    private var restarting = false

    private val startSetup: ActivityResultLauncher<Unit> =
        registerForActivityResult(SetupContract()) { setupWizardCb(it) }

    private val resumeSetup: ActivityResultLauncher<Unit> =
        registerForActivityResult(ResumeSetupContract()) { setupWizardCb(it) }

    private val selectApk: ActivityResultLauncher<Array<String>> =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { onApkSelected(it) }

    private val tryStartWorkService: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            tryStartWorkServiceCb(it)
        }

    private val bindWorkService: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            bindWorkServiceCb(it)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            ShelterTheme {
                if (isReady.value) {
                    MainScreen(
                        serviceMain = serviceMain.value,
                        serviceWork = serviceWork.value,
                        onInstallApk = { selectApk.launch(arrayOf("application/vnd.android.package-archive")) },
                    )
                } else {
                    // Empty surface while binding; the original showed nothing
                    // until the view pager was built.
                }
            }
        }

        if (ProfileManager.isProfileOwner(this)) {
            // The work-profile copy of the app must not host the main UI.
            finish()
            return
        }

        init()
    }

    private fun init() {
        if (settings.syncGetBoolean(SettingsStore.Keys.IS_SETTING_UP) &&
            !ProfileManager.isWorkProfileAvailable(this, settings)
        ) {
            resumeSetup.launch(Unit)
        } else if (!settings.syncGetBoolean(SettingsStore.Keys.HAS_SETUP)) {
            startSetup.launch(Unit)
        } else {
            applySettings()
            bindServices()
        }
    }

    private fun setupWizardCb(result: Boolean) {
        if (result) {
            init()
        } else {
            finish()
        }
    }

    private fun applySettings() {
        ProfileManager.applyProfileSettings(this, settings)
    }

    private fun bindServices() {
        (application as ShelterApp).bindShelterService(object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                serviceMain.value = IShelterService.Stub.asInterface(binder)
                tryStartWorkService()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Handled by the periodic ping / onResume restart path.
            }
        }, false)
    }

    private fun tryStartWorkService() {
        val intent = Intent(Actions.TRY_START_SERVICE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }

        try {
            Utility.transferIntentToProfile(this, intent)
        } catch (_: IllegalStateException) {
            // No work profile at all (not just disabled).
            settings.syncSetBoolean(SettingsStore.Keys.HAS_SETUP, false)
            Toast.makeText(this, R.string.work_profile_not_found, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        tryStartWorkService.launch(intent)
    }

    private fun tryStartWorkServiceCb(result: ActivityResult) {
        if (result.resultCode == Activity.RESULT_OK) {
            bindWorkService()
        } else {
            Toast.makeText(this, R.string.work_mode_disabled, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun bindWorkService() {
        val intent = Intent(Actions.START_SERVICE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        Utility.transferIntentToProfile(this, intent)
        bindWorkService.launch(intent)
    }

    private fun bindWorkServiceCb(result: ActivityResult) {
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            Toast.makeText(this, R.string.work_mode_disabled, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val extra = data.getBundleExtra("extra")
        val binder = extra?.getBinder("service")
        if (binder == null) {
            Toast.makeText(this, R.string.work_mode_disabled, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        serviceWork.value = IShelterService.Stub.asInterface(binder)
        registerStartActivityProxies()
        startKiller()
        isReady.value = true
    }

    private fun registerStartActivityProxies() {
        try {
            serviceMain.value?.setStartActivityProxy(object : IStartActivityProxy.Stub() {
                override fun startActivity(intent: Intent?) {
                    intent ?: return
                    startActivity(intent)
                }
            })

            serviceWork.value?.setStartActivityProxy(object : IStartActivityProxy.Stub() {
                override fun startActivity(intent: Intent?) {
                    intent ?: return
                    // Resolve the DummyActivity component in the main profile,
                    // then keep the rest of the intent (action/extras) intact.
                    val dummyIntent = Intent(intent.action)
                    Utility.transferIntentToProfileUnsigned(this@MainActivity, dummyIntent)
                    intent.component = dummyIntent.component
                    startActivity(intent)
                }
            })
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    private fun startKiller() {
        val main = serviceMain.value?.asBinder() ?: return
        val work = serviceWork.value?.asBinder() ?: return
        val bundle = Bundle().apply {
            putBinder("main", main)
            putBinder("work", work)
        }
        startService(Intent(this, KillerService::class.java).putExtra("extra", bundle))
    }

    private fun servicesAlive(): Boolean {
        return try {
            serviceMain.value?.ping()
            serviceWork.value?.ping()
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun onResume() {
        super.onResume()
        if (serviceMain.value != null && serviceWork.value != null && !servicesAlive()) {
            // Kill the old services before recreating so the new activity does
            // not inherit stale binders.
            doOnDestroy()
            restarting = true
            val intent = intent
            finish()
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!restarting) {
            doOnDestroy()
        }
    }

    private fun doOnDestroy() {
        stopService(Intent(this, KillerService::class.java))
        Utility.killShelterServices(serviceMain.value, serviceWork.value)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && serviceMain.value != null) {
            // Do not keep the activity in the background; the foreground
            // service in the work profile should die with the UI.
            finish()
        }
    }

    private fun onApkSelected(uri: Uri?) {
        if (uri == null) return
        val service = serviceWork.value ?: return
        val proxy = UriForwardProxy(applicationContext, uri)

        try {
            service.installApk(proxy, object : IAppInstallCallback.Stub() {
                override fun callback(result: Int) {
                    runOnUiThread {
                        if (result == Activity.RESULT_OK) {
                            Toast.makeText(
                                this@MainActivity,
                                R.string.install_app_to_profile_success,
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            })
        } catch (_: RemoteException) {
            // Nothing useful to tell the user from here.
        }
    }

    private class SetupContract : ActivityResultContract<Unit, Boolean>() {
        override fun createIntent(context: android.content.Context, input: Unit): Intent =
            Intent(context, SetupActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            resultCode == Activity.RESULT_OK
    }

    private class ResumeSetupContract : ActivityResultContract<Unit, Boolean>() {
        override fun createIntent(context: android.content.Context, input: Unit): Intent =
            Intent(context, SetupActivity::class.java).setAction(SetupActivity.ACTION_RESUME_SETUP)

        override fun parseResult(resultCode: Int, intent: Intent?): Boolean =
            resultCode == Activity.RESULT_OK
    }
}
