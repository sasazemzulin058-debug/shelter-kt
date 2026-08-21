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
import net.typeblog.shelter.profile.DummyActivity
import net.typeblog.shelter.util.Utility

class DeviceAdminReceiver : DeviceAdminReceiver() {
    companion object {
        private const val NOTIFICATION_ID = 114514
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        // After Oreo, the activity intent ACTION_PROVISIONING_SUCCESSFUL is used
        // for finalization; as an activity intent it is far more reliable (and
        // less hacky) than doing it in a BroadcastReceiver. That is handled by
        // FinalizeActivity, so ignore the event here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) return
        // Complex logic in a BroadcastReceiver is not reliable; delegate
        // finalization to DummyActivity.
        val launch = Intent(context.applicationContext, DummyActivity::class.java)
        launch.action = Actions.FINALIZE_PROVISION
        launch.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        // Delegate starting the activity to a notification to work around
        // background limitations on custom OEM ROMs (MIUI / EMUI).
        val notification = Utility.buildNotification(
            context, true,
            "shelter-finish-provision",
            context.getString(R.string.finish_provision_title),
            context.getString(R.string.finish_provision_desc),
            R.drawable.ic_notification_white_24dp
        )
        notification.contentIntent = PendingIntent.getActivity(
            context, 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        notification.flags = notification.flags or Notification.FLAG_AUTO_CANCEL
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, notification)
    }
}
