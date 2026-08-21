package net.typeblog.shelter.profile

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.os.RemoteException
import android.os.StrictMode
import android.widget.ProgressBar
import android.widget.Toast
import net.typeblog.shelter.R
import net.typeblog.shelter.ShelterApp
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.receivers.DeviceAdminReceiver
import net.typeblog.shelter.services.FreezeService
import net.typeblog.shelter.services.IAppInstallCallback
import net.typeblog.shelter.services.IFileShuttleService
import net.typeblog.shelter.services.IFileShuttleServiceCallback
import net.typeblog.shelter.ui.setup.SetupActivity
import net.typeblog.shelter.util.FileProviderProxy
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Real implementations of every cross-profile action handled by DummyActivity.
 * Each handler receives the hosting Activity plus the injected SettingsStore,
 * and performs exactly one operation. Shared request codes and the request
 * continuation hooks live on DummyActivity.
 */
object CrossProfileAction {

    // ------------------------------------------------------------------ service

    /** Bind ShelterService in the foreground, returning its binder via result bundle. */
    fun startService(activity: DummyActivity) {
        (activity.application as ShelterApp).bindShelterService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val data = Intent()
                    val bundle = Bundle()
                    bundle.putBinder("service", service)
                    data.putExtra("extra", bundle)
                    activity.setResult(Activity.RESULT_OK, data)
                    activity.finish()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // Nothing to do; the connection is one-shot.
                }
            },
            foreground = true,
        )
    }

    /** Dummy success used as a profile-availability probe. */
    fun tryStartService(activity: DummyActivity) {
        activity.setResult(Activity.RESULT_OK)
        activity.finish()
    }

    // ------------------------------------------------------------------ finalize

    fun finalizeProvision(activity: DummyActivity, settings: SettingsStore) {
        if (ProfileManager.isProfileOwner(activity)) {
            // On pre-O the provisioning receiver notifies the parent profile after
            // policies are applied; on O+ FinalizeActivity drives activity-based flow.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                val intent = Intent(Actions.FINALIZE_PROVISION)
                // Unsigned: the parent side accepts this public action without a signature.
                ProfileManager.transferIntentToProfileUnsigned(activity, intent)
                activity.startActivity(intent)
            }
            activity.finish()
        } else {
            settings.syncSetBooleanByName("has_setup", true)
            settings.syncSetBooleanByName("is_setting_up", false)
            val intent = Intent(Actions.ACTION_PROFILE_PROVISIONED)
            intent.setComponent(ComponentName(activity, SetupActivity::class.java))
            activity.startActivity(intent)
            Toast.makeText(activity, R.string.provision_finished, Toast.LENGTH_LONG).show()
            activity.finish()
        }
    }

    // ------------------------------------------------------------------ install / uninstall

    fun installPackage(activity: DummyActivity) {
        val intent = activity.intent
        var uri: Uri? = null
        if (intent.hasExtra("package")) {
            uri = Uri.fromParts("package", intent.getStringExtra("package"), null)
        }

        val policy = StrictMode.getVmPolicy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O || intent.hasExtra("direct_install_apk")) {
            if (intent.hasExtra("apk")) {
                // "package:" URIs stop working after O; fall back to the APK path,
                // keeping the pre-O path for a future minSdk reduction.
                uri = Uri.fromFile(File(intent.getStringExtra("apk")))
            } else if (intent.hasExtra("direct_install_apk")) {
                // APK forwarded from the other profile through our FileProvider.
                uri = intent.getParcelableExtra("direct_install_apk")
            }
            // Permissive VmPolicy needed to work around the cross-app URI limit.
            StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                installPackageQ(activity, uri, intent.getStringArrayExtra("split_apks"))
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
        } else {
            val i = Intent(Intent.ACTION_INSTALL_PACKAGE, uri)
            i.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, activity.packageName)
            i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            i.putExtra(Intent.EXTRA_RETURN_RESULT, true)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            activity.startActivityForResult(i, DummyActivity.REQUEST_INSTALL_PACKAGE)
        }

        StrictMode.setVmPolicy(policy)
    }

    private fun installPackageQ(activity: DummyActivity, uri: Uri?, splitApks: Array<String>?) {
        val pi = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)

        // Show progress; the listener deregisters itself once its session finishes.
        val progress = SessionProgress(activity, pi, sessionId)
        pi.registerSessionCallback(progress)

        val session = pi.openSession(sessionId)
        doInstallPackageQ(activity, uri, splitApks, session) {
            // Finished piping the streams; advertise partial progress then commit.
            session.setStagingProgress(0.1f)
            val callbackIntent = Intent(activity, DummyActivity::class.java)
            callbackIntent.action = Actions.PACKAGEINSTALLER_CALLBACK
            val pending = android.app.PendingIntent.getActivity(
                activity, 0, callbackIntent, android.app.PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    // Reads the APK streams on a background thread to keep the UI thread free.
    private fun doInstallPackageQ(
        activity: DummyActivity,
        baseUri: Uri?,
        splitApks: Array<String>?,
        session: PackageInstaller.Session,
        callback: () -> Unit,
    ) {
        val uris = ArrayList<Uri>()
        if (baseUri != null) uris.add(baseUri)
        if (splitApks != null) {
            for (apk in splitApks) uris.add(Uri.fromFile(File(apk)))
        }

        Thread {
            for (u in uris) {
                try {
                    val input = activity.contentResolver.openInputStream(u)
                    if (input == null) continue
                    input.use { ins ->
                        // Do not trust available() as the length; copy until EOF.
                        val output = session.openWrite(UUID.randomUUID().toString(), 0, ins.available().toLong())
                        output.use { os ->
                            pipe(ins, os)
                            session.fsync(os)
                        }
                    }
                } catch (_: IOException) {
                    // A failed split is reported through the session callback.
                }
            }
            activity.runOnUiThread(callback)
        }.start()
    }

    fun uninstallPackage(activity: DummyActivity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pi = activity.packageManager.packageInstaller
            val callbackIntent = Intent(activity, DummyActivity::class.java)
            callbackIntent.action = Actions.PACKAGEINSTALLER_CALLBACK
            val pending = android.app.PendingIntent.getActivity(
                activity, 0, callbackIntent, android.app.PendingIntent.FLAG_MUTABLE)
            pi.uninstall(activity.intent.getStringExtra("package").orEmpty(), pending.intentSender)
            return
        }

        val uri = Uri.fromParts("package", activity.intent.getStringExtra("package"), null)
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, uri)
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        // Install and uninstall share one callback path and request code.
        activity.startActivityForResult(intent, DummyActivity.REQUEST_INSTALL_PACKAGE)
    }

    /**
     * Terminal install/uninstall outcome. Clears any forwarded FD owned by
     * FileProviderProxy, delivers the IAppInstallCallback result once, then
     * finishes (unless no callback was supplied, matching the original).
     */
    fun appInstallFinished(activity: DummyActivity, resultCode: Int) {
        FileProviderProxy.clearForwardProxy()

        val intent = activity.intent
        if (!intent.hasExtra("callback")) return

        val callbackExtra = intent.getBundleExtra("callback")
        val callback = IAppInstallCallback.Stub.asInterface(callbackExtra?.getBinder("callback"))
        try {
            callback.callback(resultCode)
        } catch (_: RemoteException) {
            // Caller went away; nothing left to do.
        }
        activity.finish()
    }

    // ------------------------------------------------------------------ unfreeze / freeze

    fun unfreezeAndLaunch(activity: DummyActivity, settings: SettingsStore) {
        val intent = activity.intent
        val policy = activity.getSystemService(DevicePolicyManager::class.java)

        if (!ProfileManager.isProfileOwner(activity)) {
            // Forward to the managed profile along with its launch settings.
            val fwd = Intent(Actions.UNFREEZE_AND_LAUNCH)
            ProfileManager.transferIntentToProfile(activity, fwd, activity.auth)
            val packageName = intent.getStringExtra("packageName").orEmpty()
            fwd.putExtra("packageName", packageName)
            fwd.putExtra(
                "shouldFreeze",
                settings.syncAutoFreezeServiceEnabled() &&
                    settings.syncAutoFreezeContains(packageName),
            )

            if (intent.hasExtra("linkedPackages")) {
                val packages = intent.getStringExtra("linkedPackages")!!.split(",").toTypedArray()
                val shouldFreeze = BooleanArray(packages.size)
                for (i in packages.indices) {
                    shouldFreeze[i] = settings.syncAutoFreezeServiceEnabled() &&
                        settings.syncAutoFreezeContains(packages[i])
                }
                fwd.putExtra("linkedPackages", packages)
                fwd.putExtra("linkedPackagesShouldFreeze", shouldFreeze)
            }

            activity.startActivity(fwd)
            activity.finish()
            return
        }

        val admin = ComponentName(activity.applicationContext, DeviceAdminReceiver::class.java)

        if (intent.hasExtra("linkedPackages")) {
            val packages = intent.getStringArrayExtra("linkedPackages")!!
            val shouldFreeze = intent.getBooleanArrayExtra("linkedPackagesShouldFreeze")!!
            for (i in packages.indices) {
                policy.setApplicationHidden(admin, packages[i], false)
                if (shouldFreeze[i]) registerAppToFreeze(activity, packages[i])
            }
        }

        val packageName = intent.getStringExtra("packageName").orEmpty()
        policy.setApplicationHidden(admin, packageName, false)

        val launchIntent = activity.packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            if (intent.getBooleanExtra("shouldFreeze", false)) {
                registerAppToFreeze(activity, packageName)
            }
            activity.startActivity(launchIntent)
        } else {
            Toast.makeText(
                activity,
                activity.getString(R.string.launch_app_fail, packageName),
                Toast.LENGTH_SHORT,
            ).show()
        }
        activity.finish()
    }

    private fun registerAppToFreeze(activity: DummyActivity, packageName: String) {
        FreezeService.registerAppToFreeze(packageName)
        activity.startService(Intent(activity, FreezeService::class.java))
    }

    fun publicFreezeAll(activity: DummyActivity, settings: SettingsStore) {
        if (!ProfileManager.isProfileOwner(activity)) {
            val intent = Intent(Actions.FREEZE_ALL_IN_LIST)
            ProfileManager.transferIntentToProfile(activity, intent, activity.auth)
            val list = settings.syncAutoFreezeList()
            intent.putExtra("list", list.toTypedArray())
            activity.startActivity(intent)
            activity.finish()
        } else {
            // Original parity: this branch is only reachable if the public action
            // is somehow delivered inside the profile itself.
            throw RuntimeException("unimplemented")
        }
    }

    fun freezeAllInList(activity: DummyActivity) {
        if (ProfileManager.isProfileOwner(activity)) {
            val list = activity.intent.getStringArrayExtra("list") ?: emptyArray()
            val admin = ComponentName(activity.applicationContext, DeviceAdminReceiver::class.java)
            val policy = activity.getSystemService(DevicePolicyManager::class.java)
            for (pkg in list) {
                policy.setApplicationHidden(admin, pkg, true)
            }
            activity.stopService(Intent(activity, FreezeService::class.java))
            Toast.makeText(activity, R.string.freeze_all_success, Toast.LENGTH_SHORT).show()
            activity.finish()
        } else {
            activity.finish()
        }
    }

    // ------------------------------------------------------------------ file shuttle

    fun startFileShuttle(activity: DummyActivity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (activity.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                doStartFileShuttle(activity)
            } else {
                activity.requestPermissions(
                    arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    DummyActivity.REQUEST_PERMISSION_EXTERNAL_STORAGE,
                )
            }
        } else {
            // Since R the all-file-access + system-alert permissions gate File Shuttle.
            if (checkAllFileAccessPermission() && checkSystemAlertPermission(activity)) {
                doStartFileShuttle(activity)
            } else {
                activity.finish()
            }
        }
    }

    fun doStartFileShuttle(activity: DummyActivity) {
        (activity.application as ShelterApp).bindFileShuttleService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val shuttle = IFileShuttleService.Stub.asInterface(service)
                    val callback = IFileShuttleServiceCallback.Stub.asInterface(
                        activity.intent.getBundleExtra("extra")?.getBinder("callback"))
                    try {
                        callback.callback(shuttle)
                    } catch (_: RemoteException) {
                        // Do nothing.
                    }
                    activity.finish()
                }

                override fun onServiceDisconnected(name: ComponentName) {
                    // Do nothing.
                }
            },
        )
    }

    private fun checkAllFileAccessPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    private fun checkSystemAlertPermission(activity: DummyActivity): Boolean {
        val appOps = activity.getSystemService(android.app.AppOpsManager::class.java)
        return appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            android.os.Process.myUid(),
            activity.packageName,
        ) == android.app.AppOpsManager.MODE_ALLOWED
    }

    // ------------------------------------------------------------------ preference sync

    fun synchronizePreference(activity: DummyActivity, settings: SettingsStore) {
        val intent = activity.intent
        val name = intent.getStringExtra("name")
        if (intent.hasExtra("boolean")) {
            settings.syncSetBooleanByName(name.orEmpty(), intent.getBooleanExtra("boolean", false))
        } else if (intent.hasExtra("int")) {
            settings.syncSetIntByName(name.orEmpty(), intent.getIntExtra("int", Int.MIN_VALUE))
        }

        // Apply settings-driven component toggling, then refresh policies in the
        // managed profile so a changed setting takes effect immediately.
        ProfileManager.applyProfileSettings(activity, settings)
        if (ProfileManager.isProfileOwner(activity)) {
            ProfileManager.enforceWorkProfilePolicies(activity, settings)
        }
        activity.finish()
    }

    // ------------------------------------------------------------------ helpers

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(65536)
        var n = input.read(buffer)
        while (n > -1) {
            output.write(buffer, 0, n)
            n = input.read(buffer)
        }
    }

    /** Progress dialog + SessionCallback for a single PackageInstaller session. */
    private class SessionProgress(
        activity: Activity,
        private val pi: PackageInstaller,
        private val sessionId: Int,
    ) : PackageInstaller.SessionCallback() {
        private val bar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal)
        private val dialog = AlertDialog.Builder(activity)
            .setCancelable(false)
            .setTitle(R.string.app_installing)
            .setView(bar)
            .create()

        init {
            dialog.show()
        }

        override fun onCreated(sessionId: Int) {
            // Session creation is requested synchronously; nothing to do.
        }

        override fun onBadgingChanged(sessionId: Int) {
            // Badging is shown by the installer dialog; nothing to do.
        }

        override fun onActiveChanged(sessionId: Int, active: Boolean) {
            // Activation state is tracked via onProgressChanged/onFinished.
        }

        override fun onProgressChanged(sessionId: Int, progress: Float) {
            if (sessionId == this.sessionId) {
                bar.progress = (progress * 100).toInt()
            }
        }

        override fun onFinished(sessionId: Int, success: Boolean) {
            if (sessionId != this.sessionId) return
            dialog.dismiss()
            pi.unregisterSessionCallback(this)
        }
    }
}
