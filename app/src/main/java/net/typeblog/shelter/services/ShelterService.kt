package net.typeblog.shelter.services

import android.app.Activity
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.typeblog.shelter.R
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.AuthManager
import net.typeblog.shelter.profile.DummyActivity
import net.typeblog.shelter.receivers.DeviceAdminReceiver
import net.typeblog.shelter.util.ApplicationInfoWrapper
import net.typeblog.shelter.util.FileProviderProxy
import net.typeblog.shelter.util.UriForwardProxy
import net.typeblog.shelter.util.Utility

class ShelterService : Service() {

    companion object {
        const val RESULT_CANNOT_INSTALL_SYSTEM_APP = 100001
        private const val NOTIFICATION_ID = 0x49a11
    }

    // Binder callbacks arrive on binder threads; all heavy work is hoisted onto
    // a service-owned scope and cancelled with the service.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var mPolicyManager: DevicePolicyManager? = null
    private var mIsProfileOwner = false
    private var mPackageManager: PackageManager? = null
    private var mAdminComponent: ComponentName? = null
    // When we need to start an activity, we need something else to do it for us
    // as per background limitation of Android 10. This proxy can only start
    // activities that are accessible to the main profile and within our own app.
    private var mStartActivityProxy: IStartActivityProxy? = null

    private val mBinder = object : IShelterService.Stub() {
        override fun ping() {
            // Do nothing, just let the other side know we are alive.
        }

        override fun stopShelterService(kill: Boolean) {
            // Dirty: give the binder a moment to flush, then stop the service and
            // possibly tear down the whole process. Service binding is owned by
            // DummyActivity/UI, so unbinding happens naturally when their
            // connection drops; stopSelf() only requests the stop.
            scope.launch {
                delay(1L)

                if (kill && !(mIsProfileOwner && FreezeService.hasPendingAppToFreeze())) {
                    // Just kill the entire process if this signal is received and
                    // the process has nothing left to do.
                    System.exit(0)
                } else {
                    stopSelf()
                }
            }
        }

        override fun getApps(callback: IGetAppsCallback?, showAll: Boolean) {
            val pm = mPackageManager ?: return
            scope.launch {
                val pmFlags = PackageManager.MATCH_DISABLED_COMPONENTS or
                    PackageManager.MATCH_UNINSTALLED_PACKAGES
                val list = pm.getInstalledApplications(pmFlags)
                    .filter { it.packageName != this@ShelterService.packageName }
                    .filter {
                        val isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val isHidden = isHidden(it.packageName)
                        val isInstalled = (it.flags and ApplicationInfo.FLAG_INSTALLED) != 0
                        val canLaunch = pm.getLaunchIntentForPackage(it.packageName) != null
                        showAll || (!isSystem && isInstalled) || isHidden || canLaunch
                    }
                    .map { ApplicationInfoWrapper(it).loadLabel(pm).setHidden(isHidden(it.packageName)) }
                    .sortedWith(
                        compareBy<ApplicationInfoWrapper>(
                            { if (it.isHidden) 1 else 0 },
                            { it.label ?: "" }
                        )
                    )

                callback ?: return@launch
                try {
                    callback.callback(ArrayList(list))
                } catch (e: RemoteException) {
                    // Do nothing; the client is gone.
                }
            }
        }

        override fun loadIcon(info: ApplicationInfoWrapper?, callback: ILoadIconCallback?) {
            scope.launch {
                val pm = mPackageManager ?: return@launch
                val icon = Utility.drawableToBitmap(info!!.info!!.loadUnbadgedIcon(pm))

                callback ?: return@launch
                try {
                    callback.callback(icon)
                } catch (e: RemoteException) {
                    // Do nothing.
                }
            }
        }

        override fun installApp(app: ApplicationInfoWrapper?, callback: IAppInstallCallback?) {
            if (!app!!.isSystem) {
                // Installing a non-system app requires firing up PackageInstaller.
                // Delegate this to DummyActivity because only it can receive a result.
                val intent = Intent(Actions.INSTALL_PACKAGE)
                intent.setComponent(ComponentName(this@ShelterService, DummyActivity::class.java))
                intent.putExtra("package", app.packageName)
                intent.putExtra("apk", app.sourceDir)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    intent.putExtra("split_apks", app.splitApks)
                }

                // Send the callback to DummyActivity.
                val callbackExtra = Bundle()
                callbackExtra.putBinder("callback", callback!!.asBinder())
                intent.putExtra("callback", callbackExtra)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                AuthManager.registerSameProcess(intent)
                mStartActivityProxy?.startActivity(intent)
            } else {
                if (mIsProfileOwner) {
                    // We can only enable system apps in our own profile.
                    val admin = mAdminComponent ?: return
                    mPolicyManager!!.enableSystemApp(admin, app.packageName)
                    // Also set the hidden state to false.
                    mPolicyManager!!.setApplicationHidden(admin, app.packageName, false)
                    callback!!.callback(Activity.RESULT_OK)
                } else {
                    callback!!.callback(RESULT_CANNOT_INSTALL_SYSTEM_APP)
                }
            }
        }

        override fun installApk(uriForwarder: UriForwardProxy?, callback: IAppInstallCallback?) {
            // Directly install an APK through a given Fd instead of an existing one.
            val intent = Intent(Actions.INSTALL_PACKAGE)
            intent.setComponent(ComponentName(this@ShelterService, DummyActivity::class.java))
            // Generate a content Uri pointing to the Fd. DummyActivity is expected
            // to release the Fd after finishing.
            val uri = FileProviderProxy.setUriForwardProxy(uriForwarder!!, "apk")
            intent.putExtra("direct_install_apk", uri)

            val callbackExtra = Bundle()
            callbackExtra.putBinder("callback", callback!!.asBinder())
            intent.putExtra("callback", callbackExtra)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            AuthManager.registerSameProcess(intent)
            mStartActivityProxy?.startActivity(intent)
        }

        override fun uninstallApp(app: ApplicationInfoWrapper?, callback: IAppInstallCallback?) {
            if (!app!!.isSystem) {
                // Similarly, fire up DummyActivity to do uninstallation for us.
                val intent = Intent(Actions.UNINSTALL_PACKAGE)
                intent.setComponent(ComponentName(this@ShelterService, DummyActivity::class.java))
                intent.putExtra("package", app.packageName)

                val callbackExtra = Bundle()
                callbackExtra.putBinder("callback", callback!!.asBinder())
                intent.putExtra("callback", callbackExtra)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                AuthManager.registerSameProcess(intent)
                mStartActivityProxy?.startActivity(intent)
            } else {
                if (mIsProfileOwner) {
                    // Essentially the same as disabling the system app; there is no
                    // way to reverse the "enableSystemApp" operation here.
                    val admin = mAdminComponent ?: return
                    mPolicyManager!!.setApplicationHidden(admin, app.packageName, true)
                    callback!!.callback(Activity.RESULT_OK)
                } else {
                    callback!!.callback(RESULT_CANNOT_INSTALL_SYSTEM_APP)
                }
            }
        }

        override fun freezeApp(app: ApplicationInfoWrapper?) {
            if (!mIsProfileOwner) {
                throw IllegalArgumentException("Cannot freeze app without being profile owner")
            }
            val admin = mAdminComponent ?: return
            mPolicyManager!!.setApplicationHidden(admin, app!!.packageName, true)
        }

        override fun unfreezeApp(app: ApplicationInfoWrapper?) {
            if (!mIsProfileOwner) {
                throw IllegalArgumentException("Cannot unfreeze app without being profile owner")
            }
            val admin = mAdminComponent ?: return
            mPolicyManager!!.setApplicationHidden(admin, app!!.packageName, false)
        }

        override fun hasUsageStatsPermission(): Boolean {
            return Utility.checkUsageStatsPermission(this@ShelterService)
        }

        override fun hasSystemAlertPermission(): Boolean {
            return Utility.checkSystemAlertPermission(this@ShelterService)
        }

        override fun hasAllFileAccessPermission(): Boolean {
            return Utility.checkAllFileAccessPermission()
        }

        override fun getCrossProfileWidgetProviders(): List<String> {
            if (!mIsProfileOwner) {
                throw IllegalStateException("Cannot access cross-profile widget providers without being profile owner")
            }
            val admin = mAdminComponent ?: return emptyList()
            return mPolicyManager!!.getCrossProfileWidgetProviders(admin) ?: emptyList()
        }

        override fun setCrossProfileWidgetProviderEnabled(pkgName: String, enabled: Boolean): Boolean {
            if (!mIsProfileOwner) {
                throw IllegalStateException("Cannot access cross-profile widget providers without being profile owner")
            }
            val admin = mAdminComponent ?: return false
            return if (enabled) {
                mPolicyManager!!.addCrossProfileWidgetProvider(admin, pkgName)
            } else {
                mPolicyManager!!.removeCrossProfileWidgetProvider(admin, pkgName)
            }
        }

        override fun setStartActivityProxy(proxy: IStartActivityProxy?) {
            mStartActivityProxy = proxy
        }

        override fun getCrossProfilePackages(): List<String> {
            if (!mIsProfileOwner) {
                throw IllegalStateException("Cannot access cross-profile packages without being profile owner")
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw IllegalStateException("Cross-profile packages support is only available on Android 11 and later")
            }
            val admin = mAdminComponent ?: return emptyList()
            return ArrayList(mPolicyManager!!.getCrossProfilePackages(admin))
        }

        override fun setCrossProfilePackages(packages: MutableList<String>?) {
            if (!mIsProfileOwner) {
                throw IllegalStateException("Cannot access cross-profile packages without being profile owner")
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                throw IllegalStateException("Cross-profile packages support is only available on Android 11 and later")
            }
            val admin = mAdminComponent ?: return
            mPolicyManager!!.setCrossProfilePackages(admin, HashSet(packages ?: emptyList()))
        }
    }

    override fun onCreate() {
        super.onCreate()
        mPolicyManager = getSystemService(DevicePolicyManager::class.java)
        mPackageManager = packageManager
        mIsProfileOwner = mPolicyManager!!.isProfileOwnerApp(packageName)
        mAdminComponent = ComponentName(applicationContext, DeviceAdminReceiver::class.java)
    }

    override fun onBind(intent: Intent): IBinder {
        if (intent.getBooleanExtra("foreground", false)) {
            setForeground()
        }
        return mBinder
    }

    override fun onUnbind(intent: Intent): Boolean {
        // Stop our foreground notification when all clients have disconnected so no
        // notification is left behind when the Shelter activity is closed.
        stopForeground(true)
        return false
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun isHidden(packageName: String): Boolean {
        val admin = mAdminComponent ?: return false
        return mIsProfileOwner && (mPolicyManager?.isApplicationHidden(admin, packageName) == true)
    }

    private fun setForeground() {
        startForeground(
            NOTIFICATION_ID,
            Utility.buildNotification(
                this,
                getString(R.string.app_name),
                getString(R.string.service_title),
                getString(R.string.service_desc),
                R.drawable.ic_notification_white_24dp
            )
        )
    }
}
