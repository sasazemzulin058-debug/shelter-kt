package net.typeblog.shelter.services

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import net.typeblog.shelter.util.Utility

// KillerService is a dirty fix to the fact that activities cannot receive any
// event about their removal from recent tasks. Since we need to kill the process
// inside the work profile when the app is closed by any means, we have to ensure
// it is killed in every possible circumstance.
class KillerService : Service() {

    private var mServiceMain: IShelterService? = null
    private var mServiceWork: IShelterService? = null
    private var mKilled = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // The extras bundle carries the two service binder references so we can
        // order them to stop. Non-sticky: if the process dies, do not resurrect it
        // with stale binders.
        val extras : Bundle? = intent?.getBundleExtra("extra")
        mServiceMain = IShelterService.Stub.asInterface(extras?.getBinder("main"))
        mServiceWork = IShelterService.Stub.asInterface(extras?.getBinder("work"))
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) {
            killEverything()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        killEverything()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        killEverything()
    }

    private fun killEverything() {
        // Kill only once no matter how many lifecycle callbacks fire.
        if (mKilled) return
        mKilled = true
        Utility.killShelterServices(mServiceMain, mServiceWork)
        // Kill this service itself.
        stopSelf()
    }
}
