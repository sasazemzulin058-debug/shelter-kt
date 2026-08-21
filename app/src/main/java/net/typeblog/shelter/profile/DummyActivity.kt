package net.typeblog.shelter.profile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import net.typeblog.shelter.data.settings.SettingsStore

/**
 * Thin cross-profile proxy activity. It owns only:
 *  - profile-owner policy initialization;
 *  - the Android 13 notification-permission continuation;
 *  - the cross-profile authentication gate;
 *  - routing to [CrossProfileDispatcher];
 *  - the PackageInstaller callback ([onNewIntent]) and pre-Q [onActivityResult].
 *
 * Every concrete action is implemented in [CrossProfileAction].
 */
class DummyActivity : Activity() {

    internal lateinit var auth: AuthManager
    private lateinit var settings: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        auth = AuthManager(this)
        settings = SettingsStore(this)
        super.onCreate(savedInstanceState)

        if (ProfileManager.isProfileOwner(this)) {
            ProfileManager.enforceWorkProfilePolicies(this, settings)
            ProfileManager.enforceUserRestrictions(this)
            ProfileManager.applyProfileSettings(this, settings)

            synchronized(DummyActivity::class.java) {
                // Skip the permission dialog during finalization; it would
                // conflict with the provisioning UI.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    !sHasRequestedPermission && Actions.FINALIZE_PROVISION != intent.action
                ) {
                    // One request per session; avoids multiple DummyActivity instances
                    // blocking each other on the permission dialog.
                    sHasRequestedPermission = true
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        requestPermissions(
                            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                            REQUEST_PERMISSION_POST_NOTIFICATIONS,
                        )
                        // Continue once the permission result returns (see below).
                        return
                    }
                }
            }
        }

        init()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        if (intent.action == Actions.PACKAGEINSTALLER_CALLBACK) {
            val status = intent.extras?.getInt(PackageInstaller.EXTRA_STATUS)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION ->
                    startActivity(intent.extras?.get(Intent.EXTRA_INTENT) as Intent)
                PackageInstaller.STATUS_SUCCESS ->
                    CrossProfileAction.appInstallFinished(this, Activity.RESULT_OK)
                else ->
                    CrossProfileAction.appInstallFinished(this, Activity.RESULT_CANCELED)
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_INSTALL_PACKAGE) {
            CrossProfileAction.appInstallFinished(this, resultCode)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        when (requestCode) {
            REQUEST_PERMISSION_EXTERNAL_STORAGE -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    CrossProfileAction.doStartFileShuttle(this)
                } else {
                    finish()
                }
            }
            REQUEST_PERMISSION_POST_NOTIFICATIONS -> {
                // Regardless of the result, continue initialization. Most
                // features work without it; some are just less reliable.
                init()
            }
            else -> super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    private fun init() {
        val intent = intent

        // First accept a pre-registered same-process request; otherwise require
        // a valid cross-profile signature (or a whitelisted unsigned action).
        if (!checkSameProcessRequest(intent)) {
            if (!auth.verifyIntent(intent)) {
                // Unauthenticated: exit immediately.
                finish()
                return
            }
        }

        CrossProfileDispatcher.dispatch(this, intent, settings)
    }

    companion object {
        const val REQUEST_INSTALL_PACKAGE = 1
        const val REQUEST_PERMISSION_EXTERNAL_STORAGE = 2
        const val REQUEST_PERMISSION_POST_NOTIFICATIONS = 3

        // Avoid requesting the notification permission more than once per session.
        private var sHasRequestedPermission = false

        // Last time DummyActivity was told a same-process call needs an unsigned
        // action, and the window within which it is honored (5s, one-shot).
        private var sLastSameProcessRequest = -1L

        /**
         * Register that an intent will be sent to this Activity without a
         * signature from within the same process. Each registration is honored
         * for at most 5 seconds and then revoked on first use.
         */
        @JvmStatic
        @Synchronized
        fun registerSameProcessRequest(intent: Intent) {
            sLastSameProcessRequest = System.currentTimeMillis()
            intent.putExtra("is_same_process", true)
        }

        private @Synchronized fun checkSameProcessRequest(intent: Intent): Boolean {
            if (!intent.getBooleanExtra("is_same_process", false)) return false
            if (sLastSameProcessRequest < 0) return false

            val ret = System.currentTimeMillis() - sLastSameProcessRequest <= 5000 &&
                intent.action in Actions.SAME_PROCESS_ACTIONS
            if (ret) {
                sLastSameProcessRequest = -1 // Revoke the registered request.
            }
            return ret
        }
    }
}
