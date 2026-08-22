package net.typeblog.shelter.profile

import android.app.Activity
import android.content.Intent
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
        } catch (error: RuntimeException) {
            Log.e(TAG, "policy compliance failed", error)
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        try {
            val finalizeIntent = Intent(this, DummyActivity::class.java).apply {
                action = Actions.FINALIZE_PROVISION
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            AuthManager(this).registerFinalizeProvision(finalizeIntent)
            startActivity(finalizeIntent)
            Log.i(TAG, "finalization:dummy_started")
        } catch (error: RuntimeException) {
            // Compliance success must not depend on cross-profile callback routing.
            Log.e(TAG, "finalization bridge unavailable", error)
        }

        setResult(RESULT_OK)
        Log.i(TAG, "compliance:result_ok")
        Log.i(TAG, "compliance:finish")
        finish()
    }
}