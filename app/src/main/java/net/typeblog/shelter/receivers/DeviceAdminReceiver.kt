package net.typeblog.shelter.receivers

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import net.typeblog.shelter.R
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.AuthManager
import net.typeblog.shelter.profile.DummyActivity
import net.typeblog.shelter.util.buildNotification

class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val NOTIFICATION_ID = 114514
        private const val TAG = "ShelterAdmin"
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "onProfileProvisioningComplete api=${Build.VERSION.SDK_INT} action=${intent.action} extrasKeys=${intent.extras?.keySet()}")
        val secret = AuthManager.provisionedSecret(intent)
        Log.i(TAG, "admin extras secretPresent=${secret != null} secretLength=${secret?.size ?: 0}")
        if (secret == null) {
            Log.e(TAG, "provisioning secret missing")
            return
        }
        AuthManager(context).installProvisionedSecret(secret)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.i(TAG, "O+ secret installed; FinalizeActivity will launch finalization")
            return
        }
        // Complex logic in a BroadcastReceiver is not reliable:
        // delegate finalization to the DummyActivity.
        val i = Intent(context.applicationContext, DummyActivity::class.java)
        i.action = Actions.FINALIZE_PROVISION
        i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        // Process-local one-shot token: only this BIND_DEVICE_ADMIN-protected
        // receiver (invoked solely by the provisioning framework) can register
        // it; DummyActivity consumes it in the same process. An arbitrary
        // exported intent cannot forge it.
        AuthManager(context).registerFinalizeProvision(i)

        // Delegate starting the activity to a notification to work around
        // background limitations, and to fix bugs on custom OSes like MIUI/EMUI.
        val notification = context.buildNotification(
            true,
            "shelter-finish-provision",
            context.getString(R.string.finish_provision_title),
            context.getString(R.string.finish_provision_desc),
            R.drawable.ic_notification_white_24dp
        )
        notification.contentIntent = PendingIntent.getActivity(
            context, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notification.flags = notification.flags or Notification.FLAG_AUTO_CANCEL
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}