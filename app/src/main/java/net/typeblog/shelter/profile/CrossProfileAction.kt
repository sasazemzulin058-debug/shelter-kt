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
import java.util.concurrent.ConcurrentHashMap

/**
 * Real implementations of every cross-profile action handled by DummyActivity.
 * Each handler receives the hosting Activity plus the injected SettingsStore,
 * and performs exactly one operation. Shared request codes and the request
 * continuation hooks live on DummyActivity.
 */
object CrossProfileAction {

    // Internal extras carried by cross-profile requests and their callbacks.
    // "extra" holds a Bundle with a binder (service or callback);
    // "callback" holds the Bundle with the IAppInstallCallback binder.
    const val EXTRA_EXTRA = "extra"
    const val EXTRA_CALLBACK = "callback"

    // One-time FileShuttle callback identity: a fresh random token the requesting
    // side generates per acquisition attempt. It is a String extra, so it is
    // covered by the intent's HMAC payload (see AuthManager.canonicalExtras),
    // binding each delivery to the specific authenticated request.
    const val EXTRA_CALLBACK_TOKEN = "callback_token"

    // ------------------------------------------------------------------ service

    /** Bind ShelterService in the foreground, returning its binder via result bundle. */
    fun startService(activity: DummyActivity) {
        (activity.application as ShelterApp).bindShelterService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val data = Intent()
                    val bundle = Bundle()
                    bundle.putBinder("service", service)
                    data.putExtra(EXTRA_EXTRA, bundle)
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
            // This branch only runs after the DummyActivity authentication gate
            // accepted FINALIZE_PROVISION — either through the process-local
            // one-shot token registered by the BIND_DEVICE_ADMIN-protected
            // finalize entry points (FinalizeActivity / the pre-O provisioning
            // receiver) or a signed delivery. Belt and braces: refuse to forward
            // anything while this profile does not yet hold the shared secret,
            // i.e. finalization was not set up through the platform extras.
            if (!activity.auth.hasSharedSecret()) {
                activity.finish()
                return
            }
            // On pre-O the provisioning receiver notifies the parent profile after
            // policies are applied; on O+ FinalizeActivity drives activity-based flow.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                val intent = Intent(Actions.FINALIZE_PROVISION)
                // Sign with the shared secret installed from the admin extras:
                // the parent side (which holds the same secret) accepts this as
                // a provable same-secret delivery — no forgeable marker, no TOFU.
                ProfileManager.transferIntentToProfile(activity, intent, activity.auth)
                activity.startActivity(intent)
            }
            activity.finish()
        } else {
            // Parent profile. Mutate the durable setup state ONLY when this
            // delivery provably shares our secret (the parent generated it and
            // placed it in the platform admin extras at provisioning launch, so
            // a valid signature proves the finalize comes from the profile it
            // provisioned) AND finalization has not already been applied.
            // This makes duplicate or spoofed FINALIZE_PROVISION deliveries
            // idempotent no-ops instead of state-flipping side effects.
            val alreadyFinalized = settings.syncGetBoolean(SettingsStore.Keys.HAS_SETUP)
            if (!alreadyFinalized && activity.auth.hasSharedSecret()) {
                settings.syncSetBoolean(SettingsStore.Keys.HAS_SETUP, true)
                settings.syncSetBoolean(SettingsStore.Keys.IS_SETTING_UP, false)
                val intent = Intent(Actions.ACTION_PROFILE_PROVISIONED)
                intent.setComponent(ComponentName(activity, SetupActivity::class.java))
                activity.startActivity(intent)
                Toast.makeText(activity, R.string.provision_finished, Toast.LENGTH_LONG).show()
            }
            activity.finish()
        }
    }

    /** Install/uninstall request entry (see DummyActivity). Consumed -> true. */
    fun dispatch(activity: Activity, intent: Intent): Boolean = when (intent.action) {
        Actions.INSTALL_PACKAGE -> {
            installPackage(activity)
            true
        }
        Actions.UNINSTALL_PACKAGE -> {
            uninstallPackage(activity)
            true
        }
        else -> false
    }

    // ------------------------------------------------------------------ install / uninstall

    fun installPackage(activity: Activity) {
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

    private fun installPackageQ(activity: Activity, uri: Uri?, splitApks: Array<String>?) {
        val pi = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = pi.createSession(params)

        // Show progress; the listener deregisters itself once its session finishes.
        val txNonce = beginInstallTransaction(sessionId)
        val progress = SessionProgress(activity, pi, sessionId)
        pi.registerSessionCallback(progress)

        val session = pi.openSession(sessionId)
        doInstallPackageQ(activity, uri, splitApks, session) {
            // Finished piping the streams; advertise partial progress then commit.
            session.setStagingProgress(0.1f)
            // Explicit component + action: the PendingIntent operation stays an
            // activity-based getActivity, and the system only ever fills status
            // extras because the PendingIntent is mutable.
            val callbackIntent = Intent(activity, DummyActivity::class.java)
            callbackIntent.action = Actions.PACKAGEINSTALLER_CALLBACK
            // Bind this callback to the live in-process transaction so a spoofed
            // PACKAGEINSTALLER_CALLBACK is rejected at the receiver.
            callbackIntent.putExtra(EXTRA_TX_NONCE, txNonce)
            activity.intent.getBundleExtra(EXTRA_CALLBACK)?.let {
                callbackIntent.putExtra(EXTRA_CALLBACK, it)
            }
            val pending = android.app.PendingIntent.getActivity(
                activity, 0, callbackIntent, android.app.PendingIntent.FLAG_MUTABLE)
            session.commit(pending.intentSender)
        }
    }

    // Reads the APK streams on a background thread to keep the UI thread free.
    private fun doInstallPackageQ(
        activity: Activity,
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

    fun uninstallPackage(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pi = activity.packageManager.packageInstaller
            val callbackIntent = Intent(activity, DummyActivity::class.java)
            callbackIntent.action = Actions.PACKAGEINSTALLER_CALLBACK
            // The uninstall callback's session id is only known once it arrives;
            // the random nonce itself is the transaction identity here.
            callbackIntent.putExtra(
                EXTRA_TX_NONCE,
                beginInstallTransaction(-1),
            )
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
    fun appInstallFinished(activity: Activity, resultCode: Int) {
        FileProviderProxy.clearForwardProxy()

        val intent = activity.intent
        if (intent.hasExtra(EXTRA_CALLBACK)) {
            val callbackExtra = intent.getBundleExtra(EXTRA_CALLBACK)
            val callback = IAppInstallCallback.Stub.asInterface(callbackExtra?.getBinder("callback"))
            try {
                callback.callback(resultCode)
            } catch (_: RemoteException) {
                // Caller went away; nothing left to do.
            }
        }
        activity.finish()
    }

    // -------------------------------------------------- package-installer callback

    /**
     * Extra carrying the opaque PackageInstaller transaction token. Present on
     * every PACKAGEINSTALLER_CALLBACK we create; a callback without it, or with
     * a token that does not match a live transaction, is unsolicited and
     * dropped without side effects.
     */
    const val EXTRA_TX_NONCE = "tx_nonce"

    // Live package-installer transactions: nonce -> bound session id.
    // -1 means "no session binding" (used by the uninstall path, whose session
    // id is unknown until the callback arrives). Process-local and random, so a
    // foreign caller cannot fabricate a token.
    private val activeInstallTransactions = ConcurrentHashMap<String, Int>()

    /** Begin a PackageInstaller transaction; returns the fresh opaque nonce. */
    @JvmStatic
    @Synchronized
    fun beginInstallTransaction(sessionId: Int): String {
        val nonce = java.util.UUID.randomUUID().toString()
        activeInstallTransactions[nonce] = sessionId
        return nonce
    }

    /** PACKAGEINSTALLER_CALLBACK entry point (see DummyActivity.onNewIntent). */
    fun onNewIntent(activity: Activity, intent: Intent) {
        handlePackageInstallerCallback(activity, intent)
    }

    /**
     * Pre-Q install/uninstall result entry point (see DummyActivity.onActivityResult).
     */
    fun onActivityResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == DummyActivity.REQUEST_INSTALL_PACKAGE) {
            appInstallFinished(activity, resultCode)
        }
    }

    /**
     * Handle a PACKAGEINSTALLER_CALLBACK. Trusted only when it carries the
     * opaque nonce of a live transaction created by this process and, for
     * installs, the exact bound session id. STATUS_PENDING_USER_ACTION is
     * non-terminal (the transaction stays live for the final status) and only
     * launches the type-checked system confirmation intent. Terminal outcomes
     * are validated and consumed atomically, so the result reaches the original
     * caller exactly once even on duplicate or concurrent delivery.
     */
    private fun handlePackageInstallerCallback(activity: Activity, intent: Intent) {
        val status = intent.extras?.getInt(PackageInstaller.EXTRA_STATUS, -1)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            if (!isLiveCallback(intent)) {
                activity.finish()
                return
            }
            // The system's own confirmation intent, type-checked before launch.
            val confirmation = intent.extras?.get(Intent.EXTRA_INTENT) as? Intent
            if (confirmation != null && confirmation.action != null) {
                confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(confirmation)
            } else {
                // No valid confirmation to show: fail the transaction.
                consumeCallback(intent)
                appInstallFinished(activity, Activity.RESULT_CANCELED)
            }
            return
        }

        // Terminal: validate and consume in one atomic step.
        if (!consumeCallback(intent)) {
            activity.finish()
            return
        }
        if (status == PackageInstaller.STATUS_SUCCESS) {
            appInstallFinished(activity, Activity.RESULT_OK)
        } else {
            appInstallFinished(activity, Activity.RESULT_CANCELED)
        }
    }

    /**
     * Non-consuming validation for the PENDING_USER_ACTION notification: the
     * transaction must still be live after this callback because the final
     * status arrives separately.
     */
    private @Synchronized fun isLiveCallback(intent: Intent): Boolean {
        val nonce = intent.getStringExtra(EXTRA_TX_NONCE) ?: return false
        val boundSession = activeInstallTransactions[nonce] ?: return false
        if (boundSession < 0) return true // uninstall path: nonce is the identity
        val delivered = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        return delivered == boundSession
    }

    /**
     * Terminal: atomically validate the callback and remove the transaction, so
     * a second delivery cannot pass. Returns true only for the first terminal
     * delivery of a live transaction.
     */
    private @Synchronized fun consumeCallback(intent: Intent): Boolean {
        val nonce = intent.getStringExtra(EXTRA_TX_NONCE) ?: return false
        val boundSession = activeInstallTransactions.remove(nonce) ?: return false
        if (boundSession < 0) return true
        val delivered = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        return delivered == boundSession
    }

    // ------------------------------------------------------------------ unfreeze / freeze

    fun unfreezeAndLaunch(activity: DummyActivity, settings: SettingsStore) {
        val intent = activity.intent
        val policy = activity.getSystemService(DevicePolicyManager::class.java)

        if (!ProfileManager.isProfileOwner(activity)) {
            // Forward to the managed profile along with its launch settings.
            val fwd = Intent(Actions.UNFREEZE_AND_LAUNCH)
            val packageName = intent.getStringExtra("packageName").orEmpty()
            val shouldFreeze =
                settings.syncAutoFreezeServiceEnabled() &&
                    settings.syncAutoFreezeContains(packageName)
            if (intent.hasExtra("linkedPackages")) {
                val packages = intent.getStringExtra("linkedPackages")!!.split(",").toTypedArray()
                val shouldFreezeList = BooleanArray(packages.size)
                for (i in packages.indices) {
                    shouldFreezeList[i] = settings.syncAutoFreezeServiceEnabled() &&
                        settings.syncAutoFreezeContains(packages[i])
                }
                fwd.putExtra("linkedPackages", packages)
                fwd.putExtra("linkedPackagesShouldFreeze", shouldFreezeList)
            }
            fwd.putExtra("packageName", packageName)
            fwd.putExtra("shouldFreeze", shouldFreeze)
            // Sign only after every extra is in place: the HMAC payload covers
            // the canonical extras at signing time, so adding extras after
            // signing would make the recipient's verification fail.
            ProfileManager.transferIntentToProfile(activity, fwd, activity.auth)

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
            // Set extras before signing: the HMAC payload covers them at
            // signing time (see unfreezeAndLaunch).
            val list = settings.syncAutoFreezeList()
            intent.putExtra("list", list.toTypedArray())
            ProfileManager.transferIntentToProfile(activity, intent, activity.auth)
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
        val intent = activity.intent
        val token = intent.getStringExtra(EXTRA_CALLBACK_TOKEN)

        // The FileShuttleService binder is only handed to a callback whose
        // request intent was authenticated with the steady-state cross-profile
        // HMAC. A TOFU bootstrap intent carries only an auth key (no signature
        // extra), so the presence of the signature extra is the discriminator;
        // the fresh one-time token additionally binds this delivery to a
        // specific live request. An arbitrary exported caller can satisfy
        // neither, so it can never obtain the service binder.
        if (intent.getByteArrayExtra(AuthManager.EXTRA_SIGNATURE) == null) {
            activity.finish()
            return
        }
        if (token.isNullOrEmpty() || !activity.consumeFileShuttleToken(token)) {
            // Missing identity or a duplicate delivery of an already-consumed
            // token: reject late/unexpected callbacks without side effects.
            activity.finish()
            return
        }

        (activity.application as ShelterApp).bindFileShuttleService(
            object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName, service: IBinder) {
                    val shuttle = IFileShuttleService.Stub.asInterface(service)
                    val callback = IFileShuttleServiceCallback.Stub.asInterface(
                        activity.intent.getBundleExtra(EXTRA_EXTRA)?.getBinder("callback"))
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
            // The transaction is deliberately NOT consumed here: the terminal
            // outcome is delivered exactly once by the PACKAGEINSTALLER_CALLBACK
            // status receiver (see handlePackageInstallerCallback), which may
            // arrive after this dialog finishes.
            dialog.dismiss()
            pi.unregisterSessionCallback(this)
        }
    }
}
