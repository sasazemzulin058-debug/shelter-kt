package net.typeblog.shelter.profile

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * Invoked by the system on Android O+ with `android.app.action.PROVISIONING_SUCCESSFUL`
 * once profile provisioning has finished. Forwards the finalization work to
 * [DummyActivity], where the actual policy application runs.
 */
class FinalizeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // FinalizeActivity is exported with the BIND_DEVICE_ADMIN permission and
        // the android.app.action.PROVISIONING_SUCCESSFUL filter, so it can only be
        // invoked by the provisioning framework. Use that as the explicit marker
        // that opens the provisioning/bootstrap gate in the managed profile.
        AuthManager(this).markBootstrapAuthorized()
        val intent = Intent(applicationContext, DummyActivity::class.java)
        intent.action = Actions.FINALIZE_PROVISION
        // verifyIntent() accepts FINALIZE_PROVISION only when the persisted
        // bootstrap gate is armed AND the intent itself carries this marker;
        // the provisioning framework (via this BIND_DEVICE_ADMIN activity) is
        // the sole legitimate source of both.
        intent.putExtra(AuthManager.EXTRA_BOOTSTRAP_ALLOWED, true)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
