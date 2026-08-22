package net.typeblog.shelter.profile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
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

    // Last FileShuttle callback token delivered on this instance. Guards the
    // FileShuttleService binder handoff against duplicate or replayed delivery
    // of the same transaction token (see consumeFileShuttleToken).
    private var mLastFileShuttleToken: String? = null

    /**
     * One-shot callback-identity consumption. Returns true only for the first
     * delivery of a given token on this activity instance; a duplicate delivery
     * of the same token (a late or replayed callback) is rejected without
     * side effects. A fresh token (a new authenticated request) is accepted.
     */
    @Synchronized
    internal fun consumeFileShuttleToken(token: String): Boolean {
        if (mLastFileShuttleToken == token) return false
        mLastFileShuttleToken = token
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        auth = AuthManager(this)
        settings = SettingsStore(this)
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate action=${intent.action} profileOwner=${ProfileManager.isProfileOwner(this)} secret=${auth.hasSharedSecret()}")
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
        setIntent(intent)
        when (intent.action) {
            Actions.FINALIZE_PROVISION,
            Actions.PACKAGEINSTALLER_CALLBACK,
            Actions.START_FILE_SHUTTLE,
            Actions.START_FILE_SHUTTLE_2 -> init()
            else -> Unit
        }
    }

    /**
     * PackageInstaller session callbacks are validated by their transaction
     * identity (opaque nonce + session id) inside CrossProfileAction; see
     * [CrossProfileAction.onNewIntent].
     */

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        CrossProfileAction.onActivityResult(this, requestCode, resultCode, data)
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
        Log.i(TAG, "init action=${intent.action} extrasKeys=${intent.extras?.keySet()}")
        if (intent.action == Actions.FINALIZE_PROVISION) {
            Log.i(TAG, "finalize intent received; secret=${auth.hasSharedSecret()}")
        }
        if (intent.action == Actions.PACKAGEINSTALLER_CALLBACK) {
            CrossProfileAction.onNewIntent(this, intent)
            return
        }
        val verified = auth.verifyIntent(intent)
        Log.i(TAG, "intent authentication verified=$verified action=${intent.action}")
        if (!verified) {
            Log.e(TAG, "rejecting unauthenticated intent action=${intent.action}")
            finish()
            return
        }
        CrossProfileDispatcher.dispatch(this, intent, settings)
    }
    companion object {
        const val REQUEST_INSTALL_PACKAGE = 1
        const val REQUEST_PERMISSION_EXTERNAL_STORAGE = 2
        const val REQUEST_PERMISSION_POST_NOTIFICATIONS = 3
        private const val TAG = "ShelterDummy"
        private var sHasRequestedPermission = false

        /**
         * Register that an intent will be delivered to this Activity from the
         * same process without a signature. Delegates to
         * [AuthManager.registerSameProcess], the single authority: it arms a
         * one-shot random token bound to the exact action and a digest of the
         * intent's primitive extras, stamped onto [intent]; honored for at most
         * 5 seconds and consumed atomically on first use.
         */
        @JvmStatic
        fun registerSameProcessRequest(intent: Intent): String =
            AuthManager.registerSameProcess(intent)
    }
}
