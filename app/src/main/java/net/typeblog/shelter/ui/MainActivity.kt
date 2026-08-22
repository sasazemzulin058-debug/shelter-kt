package net.typeblog.shelter.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import net.typeblog.shelter.R
import net.typeblog.shelter.ShelterApp
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.AuthManager
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
 * Root activity for Shelter.
 *
 * Owns the lifecycle-bound service connections (no binders in ViewModels), drives
 * the main/work service startup chain, registers start-activity proxies, starts
 * [KillerService], and reacts to service death by restarting itself. The Compose UI
 * in [MainScreen] receives the service handles and dispatches all user actions.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ShelterMain"

        internal fun shouldProbeWorkProfile(isSettingUp: Boolean, hasSetup: Boolean): Boolean =
            isSettingUp || !hasSetup
    }

    @Inject
    lateinit var auth: AuthManager

    @Inject
    lateinit var settings: SettingsStore

    private var serviceMain: IShelterService? by mutableStateOf(null)
    private var serviceWork: IShelterService? by mutableStateOf(null)

    // Avoid double-killing services while the activity is restarting itself.
    private var restarting = false

    private val setupLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            init()
        } else {
            finish()
        }
    }

    private val tryStartWorkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            bindWorkService()
        } else {
            Toast.makeText(this, R.string.work_mode_disabled, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val bindWorkLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            val binder = result.data?.getBundleExtra("extra")?.getBinder("service")
            serviceWork = IShelterService.Stub.asInterface(binder)
            onServicesReady()
        }
    }

    private val apkPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) installApk(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (ProfileManager.isProfileOwner(this)) {
            // The main activity should never be shown inside the work profile.
            finish()
            return
        }

        setContent {
            ShelterTheme {
                MainScreen(
                    serviceMain = serviceMain,
                    serviceWork = serviceWork,
                    onInstallApk = {
                        apkPicker.launch(arrayOf("application/vnd.android.package-archive"))
                    },
                )
            }
        }

        init()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i(TAG, "onNewIntent action=${intent.action}")
        if (Actions.ACTION_PROFILE_PROVISIONED == intent.action) {
            init()
        }
    }

    private fun init() {
        val isSettingUp = settings.syncGetBoolean(SettingsStore.Keys.IS_SETTING_UP)
        val hasSetup = settings.syncGetBoolean(SettingsStore.Keys.HAS_SETUP)
        val workProfileAvailable =
            if (shouldProbeWorkProfile(isSettingUp, hasSetup)) {
                ProfileManager.isWorkProfileAvailable(this, settings)
            } else false
        Log.i(TAG, "init isSettingUp=$isSettingUp hasSetup=$hasSetup profileOwner=${ProfileManager.isProfileOwner(this)} workProfileAvailable=$workProfileAvailable")

        if (isSettingUp && !workProfileAvailable) {
            Log.i(TAG, "launching resume setup")
            val intent = Intent(this, SetupActivity::class.java)
                .setAction(Actions.ACTION_RESUME_SETUP)
            setupLauncher.launch(intent)
        } else if (!hasSetup && workProfileAvailable) {
            // The system may finish provisioning before the previous setup
            // process persists its flags. Recover the existing profile instead
            // of starting a second provisioning request.
            Log.i(TAG, "existing work profile recovered")
            ProfileManager.applyProfileSettings(this, settings)
            bindServices()
        } else if (!hasSetup) {
            Log.i(TAG, "launching initial setup")
            setupLauncher.launch(Intent(this, SetupActivity::class.java))
        } else {
            Log.i(TAG, "setup complete; binding services")
            ProfileManager.applyProfileSettings(this, settings)
            bindServices()
        }
    }

    private fun bindServices() {
        (application as ShelterApp).bindShelterService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    serviceMain = IShelterService.Stub.asInterface(service)
                    tryStartWorkService()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    serviceMain = null
                }
            },
            foreground = false,
        )
    }

    private fun tryStartWorkService() {
        val intent = Intent(Actions.TRY_START_SERVICE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        try {
            ProfileManager.transferIntentToProfile(this, intent, auth)
        } catch (e: IllegalStateException) {
            // No work profile at all (distinct from work mode being disabled).
            settings.syncSetBoolean(SettingsStore.Keys.HAS_SETUP, false)
            Toast.makeText(this, R.string.work_profile_not_found, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        tryStartWorkLauncher.launch(intent)
    }

    private fun bindWorkService() {
        val intent = Intent(Actions.START_SERVICE).apply {
            addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        }
        ProfileManager.transferIntentToProfile(this, intent, auth)
        bindWorkLauncher.launch(intent)
    }

    private fun onServicesReady() {
        registerStartActivityProxies()
        startKiller()
        // Compose recomposes with the new service handles; MainScreen binds them to
        // its ViewModels and refreshes the lists.
    }

    private fun startKiller() {
        val main = serviceMain ?: return
        val work = serviceWork ?: return
        val intent = Intent(this, KillerService::class.java).apply {
            putExtra(
                "extra",
                Bundle().apply {
                    putBinder("main", main.asBinder())
                    putBinder("work", work.asBinder())
                },
            )
        }
        startService(intent)
    }

    private fun registerStartActivityProxies() {
        val main = serviceMain ?: return
        val work = serviceWork ?: return
        try {
            main.setStartActivityProxy(object : IStartActivityProxy.Stub() {
                override fun startActivity(intent: Intent) {
                    this@MainActivity.startActivity(intent)
                }
            })
            work.setStartActivityProxy(object : IStartActivityProxy.Stub() {
                override fun startActivity(intent: Intent) {
                    // Resolve the counterpart DummyActivity without extras so the package
                    // manager can find it across the profile boundary.
                    val dummy = Intent(intent.action)
                    ProfileManager.transferIntentToProfileUnsigned(this@MainActivity, dummy)
                    intent.component = dummy.component
                    this@MainActivity.startActivity(intent)
                }
            })
        } catch (e: RemoteException) {
            throw RuntimeException(e)
        }
    }

    private fun servicesAlive(): Boolean {
        return try {
            serviceMain?.ping()
            serviceWork?.ping()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun installApk(uri: Uri) {
        val work = serviceWork ?: return
        val proxy = UriForwardProxy(applicationContext, uri)
        try {
            work.installApk(
                proxy,
                object : IAppInstallCallback.Stub() {
                    override fun callback(result: Int) {
                        runOnUiThread {
                            if (result == RESULT_OK) {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.install_app_to_profile_success,
                                    Toast.LENGTH_LONG,
                                ).show()
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    R.string.setup_failed,
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
            )
        } catch (e: RemoteException) {
            Toast.makeText(this, R.string.service_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (serviceMain != null && serviceWork != null && !servicesAlive()) {
            // Services died (e.g. KillerService was destroyed). Kill stale binders and
            // restart so the new activity can bind fresh services.
            doOnDestroy()
            restarting = true
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
        Utility.killShelterServices(serviceMain, serviceWork)
        serviceMain = null
        serviceWork = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND && serviceMain != null) {
            // Finishing in the background ensures the work-profile foreground service
            // is torn down along with this activity.
            finish()
        }
    }
}
