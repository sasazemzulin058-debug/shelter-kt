package net.typeblog.shelter

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import dagger.hilt.android.HiltAndroidApp
import net.typeblog.shelter.services.FileShuttleService
import net.typeblog.shelter.services.ShelterService

@HiltAndroidApp
class ShelterApp : Application() {

    private var shelterServiceConnection: ServiceConnection? = null
    private var fileShuttleServiceConnection: ServiceConnection? = null

    /**
     * Bind ShelterService across the profile boundary. Only one connection is
     * held at a time; [foreground] marks the service foreground on the other
     * side so it survives the short-lived proxy activity.
     */
    fun bindShelterService(conn: ServiceConnection, foreground: Boolean) {
        unbindShelterService()
        val intent = Intent(applicationContext, ShelterService::class.java)
        intent.putExtra("foreground", foreground)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        shelterServiceConnection = conn
    }

    fun bindFileShuttleService(conn: ServiceConnection) {
        unbindFileShuttleService()
        val intent = Intent(applicationContext, FileShuttleService::class.java)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        fileShuttleServiceConnection = conn
    }

    fun unbindShelterService() {
        shelterServiceConnection?.let {
            try {
                unbindService(it)
            } catch (_: Exception) {
                // Already unbound; nothing to roll back.
            }
        }
        shelterServiceConnection = null
    }

    fun unbindFileShuttleService() {
        fileShuttleServiceConnection?.let {
            try {
                unbindService(it)
            } catch (_: Exception) {
                // Already unbound; nothing to roll back.
            }
        }
        fileShuttleServiceConnection = null
    }
}
