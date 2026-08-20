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
        val intent = Intent(applicationContext, DummyActivity::class.java)
        intent.action = Actions.FINALIZE_PROVISION
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        startActivity(intent)
        finish()
    }
}
