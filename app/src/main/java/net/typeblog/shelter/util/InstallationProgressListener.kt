package net.typeblog.shelter.util

import android.app.Activity
import android.content.pm.PackageInstaller
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import net.typeblog.shelter.R

// A listener that shows a progress dialog for one session, then removes itself
// once that session has finished. Only cares about the one sessionId provided.
// The caller is responsible for registering the callback.
class InstallationProgressListener(
    activity: Activity,
    private val mPi: PackageInstaller,
    private val mSessionId: Int
) : PackageInstaller.SessionCallback() {
    private var mDialog: AlertDialog? = null
    private var mProgress: ProgressBar? = null

    init {
        val layoutId = activity.resources.getIdentifier("progress_dialog", "layout", activity.packageName)
        val progressId = activity.resources.getIdentifier("progress", "id", activity.packageName)
        val layout = LayoutInflater.from(activity).inflate(
            layoutId,
            activity.window.decorView as ViewGroup?,
            false
        ) as ViewGroup
        mProgress = layout.findViewById(progressId)
        mDialog = AlertDialog.Builder(activity)
            .setCancelable(false)
            .setTitle(R.string.app_installing)
            .setView(layout)
            .create()
        mDialog?.show()
    }

    override fun onCreated(sessionId: Int) = Unit

    override fun onBadgingChanged(sessionId: Int) = Unit

    override fun onActiveChanged(sessionId: Int, active: Boolean) = Unit

    override fun onProgressChanged(sessionId: Int, progress: Float) {
        mProgress?.setProgress((progress * 100).toInt())
    }

    override fun onFinished(sessionId: Int, success: Boolean) {
        if (sessionId != mSessionId) return
        mDialog?.hide()
        mPi.unregisterSessionCallback(this)
    }
}
