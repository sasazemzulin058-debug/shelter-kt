package net.typeblog.shelter.receivers

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import net.typeblog.shelter.R
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.AuthManager
import net.typeblog.shelter.profile.DummyActivity
import net.typeblog.shelter.util.buildNotification

class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val NOTIFICATION_ID = 114514
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)

        // After Oreo, we use the activity intent ACTION_PROVISIONING_SUCCESSFUL
        // for finalization. As it is an activity intent, it is way more
        // reliable (and less hacky) than doing it in a BroadcastReceiver.
        // This is handled by FinalizeActivity, and thus we should ignore the
        // event here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return

        // Fail closed: the shared secret must arrive through the platform
        // provisioning admin extras bundle placed by SetupActivity. Without it
        // there is nothing to finalize against — no secret, no finalization.
        val secret = AuthManager.provisionedSecret(intent) ?: return
        AuthManager(context).installProvisionedSecret(secret)

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