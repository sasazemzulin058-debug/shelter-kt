package net.typeblog.shelter.services

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import net.typeblog.shelter.R
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.receivers.DeviceAdminReceiver
import net.typeblog.shelter.util.Utility

// This service registers a screen-off listener that will be called when the user
// locks the screen. When that happens it freezes all the apps that the user
// launched through Unfreeze & Launch during the last session.
class FreezeService : Service() {

    companion object {
        // Keep a process-level pending list; DummyActivity adds to it via the
        // static methods below. We don't need another process, so the static
        // context is sufficient.
        private val sAppToFreeze = ArrayList<String>()

        @JvmStatic
        @Synchronized
        fun registerAppToFreeze(app: String) {
            if (!sAppToFreeze.contains(app)) {
                sAppToFreeze.add(app)
            }
        }

        @JvmStatic
        @Synchronized
        fun hasPendingAppToFreeze(): Boolean = sAppToFreeze.isNotEmpty()

        // An app being inactive for this amount of time will be frozen.
        private const val APP_INACTIVE_TIMEOUT = 1_000L

        private const val NOTIFICATION_ID = 0xe49c0
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var settingsStore: SettingsStore

    // The receiver of the screen-off event.
    private val mLockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Save usage statistics right now! We need the statics at this moment
            // for "skipping foreground apps": no app is foreground after the screen
            // is locked.
            mScreenLockTime = System.currentTimeMillis()
            scope.launch {
                val prefs = settingsStore.data.first()
                val skipForeground = prefs[SettingsStore.Keys.DONT_FREEZE_FOREGROUND] ?: false
                val delaySeconds = ((prefs[SettingsStore.Keys.AUTO_FREEZE_DELAY] ?: 0) as Number).toLong().coerceAtLeast(0L)

                if (skipForeground && Utility.checkUsageStatsPermission(this@FreezeService)) {
                    val usm = getSystemService(UsageStatsManager::class.java)
                    val statsMap = usm.queryAndAggregateUsageStats(
                        mScreenLockTime - APP_INACTIVE_TIMEOUT, mScreenLockTime
                    )
                    mUsageStats = HashMap(statsMap)
                }

                // Delay the work so it can be canceled if the screen gets unlocked
                // before the delay passes.
                mAlarmManager!!.set(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + delaySeconds * 1000L,
                    null, mFreezeWork, null
                )
                registerExportedReceiver(mUnlockReceiver, Intent.ACTION_SCREEN_ON)
            }
        }
    }

    // Cancels the freeze job if the designated delay has not yet passed.
    private val mUnlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            mAlarmManager!!.cancel(mFreezeWork)
        }
    }

    // Usage statistics when the screen was locked; kept here because we need the
    // data at the moment the screen locks. Empty when not needed.
    private var mUsageStats = HashMap<String, UsageStats>()
    private var mScreenLockTime = -1L

    private var mAlarmManager: AlarmManager? = null

    private val mFreezeWork = object : AlarmManager.OnAlarmListener {
        override fun onAlarm() {
            synchronized(FreezeService::class.java) {
                // Cancel the unlock receiver first - the delay has passed if this
                // work has executed.
                unregisterReceiverSafely(mUnlockReceiver)

                if (sAppToFreeze.isNotEmpty()) {
                    val dpm = getSystemService(DevicePolicyManager::class.java)
                    val adminComponent =
                        ComponentName(this@FreezeService, DeviceAdminReceiver::class.java)
                    for (app in sAppToFreeze) {
                        var shouldFreeze = true
                        mUsageStats[app]?.let { stats ->
                            if (mScreenLockTime - stats.lastTimeUsed <= APP_INACTIVE_TIMEOUT &&
                                stats.totalTimeInForeground >= APP_INACTIVE_TIMEOUT
                            ) {
                                // Don't freeze foreground apps if requested.
                                shouldFreeze = false
                            }
                        }
                        if (shouldFreeze) {
                            dpm.setApplicationHidden(adminComponent, app, true)
                        }
                    }
                    sAppToFreeze.clear()
                }
                stopSelf()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        mAlarmManager = getSystemService(AlarmManager::class.java)
        registerExportedReceiver(mLockReceiver, Intent.ACTION_SCREEN_OFF)
        // Use a foreground notification to keep this service alive until the
        // screen is locked.
        setForeground()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiverSafely(mLockReceiver)
        unregisterReceiverSafely(mUnlockReceiver)
        scope.cancel()
    }

    override fun onBind(intent: Intent): IBinder? = null

    private fun registerExportedReceiver(receiver: BroadcastReceiver, action: String) {
        val filter = IntentFilter(action)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterReceiverSafely(receiver: BroadcastReceiver) {
        try {
            unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was already unregistered (e.g. freeze work ran without a
            // registered unlock receiver); nothing to do.
        }
    }

    private fun setForeground() {
        val notification = Utility.buildNotification(
            this,
            getString(R.string.service_auto_freeze_title),
            getString(R.string.service_auto_freeze_title),
            getString(R.string.service_auto_freeze_desc),
            R.drawable.ic_lock_open_white_24dp
        )

        // Add a quick action to freeze all applications in the list right now by
        // reusing the "freeze all" desktop shortcut intent.
        val intentFreeze = Intent(Actions.PUBLIC_FREEZE_ALL)
        // The shortcut intent lives in the main profile while this service runs in
        // the work profile.
        Utility.transferIntentToProfileUnsigned(this, intentFreeze)
        notification.actions = arrayOf(
            Notification.Action.Builder(
                null, getString(R.string.service_auto_freeze_now),
                PendingIntent.getActivity(
                    this, 0, intentFreeze, PendingIntent.FLAG_IMMUTABLE
                )
            ).build()
        )

        // Show the notification and begin foreground operation.
        startForeground(NOTIFICATION_ID, notification)
    }
}
