package net.typeblog.shelter.profile

import android.app.Activity
import android.os.Bundle
import android.util.Log
import net.typeblog.shelter.data.settings.SettingsStore

/**
 * DPC compliance activity for Android provisioning.
 *
 * Policy setup completes before RESULT_OK returns to ManagedProvisioning.
 */
class FinalizeActivity : Activity() {
    companion object {
        private const val TAG = "ShelterFinalize"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate action=${intent.action} extrasKeys=${intent.extras?.keySet()}")

        val deliveredSecret = AuthManager.provisionedSecret(intent)
        Log.i(TAG, "admin extras secretPresent=${deliveredSecret != null} secretLength=${deliveredSecret?.size ?: 0}")
        if (deliveredSecret != null) {
            AuthManager(this).installProvisionedSecret(deliveredSecret)
        }
        if (!AuthManager(this).hasSharedSecret()) {
            Log.e(TAG, "provisioning secret unavailable")
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        try {
            Log.i(TAG, "policy:start")
            val settings = SettingsStore(this)
            ProfileManager.enforceWorkProfilePolicies(this, settings)
            Log.i(TAG, "policy:work_filters:done")
            ProfileManager.enforceUserRestrictions(this)
            Log.i(TAG, "policy:restrictions:done")
            ProfileManager.applyProfileSettings(this, settings)
            Log.i(TAG, "policy:settings:done")

            setResult(RESULT_OK)
            Log.i(TAG, "compliance:result_ok")
        } catch (error: RuntimeException) {
            Log.e(TAG, "policy compliance failed", error)
            setResult(RESULT_CANCELED)
        }
        Log.i(TAG, "compliance:finish")
        finish()
    }
}