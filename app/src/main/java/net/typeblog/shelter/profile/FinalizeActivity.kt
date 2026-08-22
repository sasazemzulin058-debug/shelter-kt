package net.typeblog.shelter.profile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import net.typeblog.shelter.R

/**
 * Invoked by the system on Android O+ with `android.app.action.PROVISIONING_SUCCESSFUL`
 * once profile provisioning has finished. Installs the shared secret delivered
 * by the provisioning framework (the admin extras bundle SetupActivity placed in
 * the provisioning intent) and forwards the finalization work to
 * [DummyActivity], where the actual policy application runs.
 *
 * FinalizeActivity is exported with the BIND_DEVICE_ADMIN permission and the
 * android.app.action.PROVISIONING_SUCCESSFUL filter, so it can only be invoked
 * by the provisioning framework — the sole legitimate source of the secret.
 * If the platform did not deliver the admin extras (no secret), finalization
 * fails closed: no policies are applied, no state is mutated.
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
            Toast.makeText(this, R.string.admin_extras_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        Log.i(TAG, "shared secret available; launching FINALIZE_PROVISION")
        val next = Intent(applicationContext, DummyActivity::class.java)
        next.action = Actions.FINALIZE_PROVISION
        AuthManager(this).registerFinalizeProvision(next)
        next.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        setResult(RESULT_OK)
        startActivity(next)
        finish()
    }
}