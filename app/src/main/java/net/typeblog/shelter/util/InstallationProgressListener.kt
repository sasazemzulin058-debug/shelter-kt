package net.typeblog.shelter.util

import android.app.Activity
import android.content.pm.PackageInstaller
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import net.typeblog.shelter.R

/**
 * Create a listener from an activity, and show a progress dialog for the
 * session. Only cares about the one session id provided here. The caller is
 * responsible for registering the callback; however, this class will remove
 * itself once the session has been finished.
 */
class InstallationProgressListener(
    activity: Activity,
    private val mPi: PackageInstaller,
    private val mSessionId: Int
) : PackageInstaller.SessionCallback() {

    private val mDialog: AlertDialog
    private val mProgress: ProgressBar

    init {
        val layout = LayoutInflater.from(activity)
            .inflate(
                R.layout.progress_dialog,
                activity.window.decorView as ViewGroup,
                false
            ) as ViewGroup
        mProgress = layout.findViewById(R.id.progress)

        mDialog = AlertDialog.Builder(activity)
            .setCancelable(false)
            .setTitle(R.string.app_installing)
            .setView(layout)
            .create()
        mDialog.show()
    }

    override fun onCreated(sessionId: Int) {
        // Unused.
    }

    override fun onBadgingChanged(sessionId: Int) {
        // Unused.
    }

    override fun onActiveChanged(sessionId: Int, active: Boolean) {
        // Unused.
    }

    override fun onProgressChanged(sessionId: Int, progress: Float) {
        mProgress.progress = (progress * 100).toInt()
    }

    override fun onFinished(sessionId: Int, success: Boolean) {
        if (sessionId != mSessionId) {
            return
        }

        mDialog.hide()
        mPi.unregisterSessionCallback(this)
    }
}