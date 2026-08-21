package net.typeblog.shelter.profile

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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
class FinalizeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fail closed: without the platform-delivered secret there is nothing to
        // finalize against — the managed profile cannot authenticate anything.
        val secret = AuthManager.provisionedSecret(intent)
        if (secret == null) {
            Toast.makeText(this, R.string.admin_extras_unavailable, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        AuthManager(this).installProvisionedSecret(secret)

        val intent = Intent(applicationContext, DummyActivity::class.java)
        intent.action = Actions.FINALIZE_PROVISION
        // Process-local one-shot token: only this BIND_DEVICE_ADMIN-protected
        // activity (invoked solely by the provisioning framework) can register
        // it; DummyActivity consumes it in the same process. An arbitrary
        // exported intent cannot forge it.
        AuthManager(this).registerFinalizeProvision(intent)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}