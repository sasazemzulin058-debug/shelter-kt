package net.typeblog.shelter.data.repository

import android.app.Activity
import android.graphics.Bitmap
import android.os.RemoteException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import net.typeblog.shelter.data.model.AppInfo
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.services.IAppInstallCallback
import net.typeblog.shelter.services.IGetAppsCallback
import net.typeblog.shelter.services.ILoadIconCallback
import net.typeblog.shelter.services.IShelterService
import net.typeblog.shelter.util.ApplicationInfoWrapper
import net.typeblog.shelter.util.UriForwardProxy
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Fine-grained install/uninstall outcome surfaced by the service callback. */
sealed class InstallResult {
    object Ok : InstallResult()
    object CannotInstallSystemApp : InstallResult()
    data class Other(val code: Int) : InstallResult()
}

/** Service binder failure (unbound/binder died). */
class ServiceUnavailableException(cause: Throwable) : Exception(cause)

/** Requested package is not in the last loaded app list. */
class AppNotFoundException(packageName: String) :
    Exception("App not in loaded list: $packageName")

/**
 * Cancellable suspend facade over the Shelter AIDL service.
 *
 * Replaces the persistent `ApplicationInfoWrapper` + `IShelterService` handling that lived in
 * AppListFragment. The AIDL layer stays untouched; this repository:
 *
 * - exposes every used service operation as a suspend function;
 * - resumes exactly once per call (late callbacks from a completed/cancelled call are suppressed);
 * - maps AIDL failures to typed exceptions;
 * - keeps the raw [ApplicationInfoWrapper] list from [getApps] in memory so parcelable-only
 *   operations (install/clone, icon load) can be re-issued without a fresh cross-process fetch.
 *
 * The `otherService` (the sibling profile's service) is used for clone-to-other-profile installs,
 * mirroring the original `getOtherService` routing. No Drawable is stored here; icons stay out of
 * durable state and are loaded on demand via [loadIcon].
 *
 * The service handles are runtime binders, not Hilt bindings: [configure] installs them once the
 * activity's ServiceConnections are alive. Until then every call fails with
 * [ServiceUnavailableException] instead of crashing on a null binder.
 */
class AppRepository @Inject constructor(
    private val settings: SettingsStore,
    @Volatile private var pruneAutoFreezeOnList: Boolean = false,
) {

    /** Runtime binder for the profile this repository serves; installed by [configure]. */
    @Volatile private var service: IShelterService? = null

    /** Sibling profile's binder (clone-to-other-profile routing); installed by [configure]. */
    @Volatile private var otherService: IShelterService? = null

    private val cache = HashMap<String, ApplicationInfoWrapper>()

    /**
     * Bind the runtime service handles for this profile. Must run before any service call;
     * an unconfigured repository fails its calls with [ServiceUnavailableException].
     */
    fun configure(
        service: IShelterService,
        otherService: IShelterService?,
        pruneAutoFreezeOnList: Boolean,
    ) {
        this.service = service
        this.otherService = otherService
        this.pruneAutoFreezeOnList = pruneAutoFreezeOnList
    }

    // ------------------------------------------------------------------ app list / icon

    /** Load apps, populating the wrapper cache. [pruneAutoFreezeOnList] mirrors the original
     * work-profile-only cleanup of the auto-freeze list against missing apps. */
    suspend fun getApps(showAll: Boolean): List<AppInfo> {
        val apps = awaitApps(showAll)
        cache.clear()
        apps.forEach { cache[it.packageName] = it }
        if (pruneAutoFreezeOnList) {
            val installed = apps.mapTo(HashSet()) { it.packageName }
            settings.syncPruneAutoFreezeList(installed)
        }
        val autoFreeze = settings.syncAutoFreezeList()
        return apps.map { it.toAppInfo(autoFreeze) }
    }

    suspend fun loadIcon(packageName: String): Bitmap {
        val wrapper = wrapper(packageName)
        return awaitIcon(wrapper)
    }

    // ------------------------------------------------------------------ install / uninstall / freeze

    /** Clone an app into the sibling profile (the original clone-to-other-profile path). */
    suspend fun installApp(packageName: String): InstallResult {
        val wrapper = wrapper(packageName)
        val target = otherService ?: requireService()
        return from(awaitInstall { cb -> target.installApp(wrapper, cb) })
    }

    suspend fun installApk(proxy: UriForwardProxy): InstallResult {
        val svc = requireService()
        return from(awaitInstall { cb -> svc.installApk(proxy, cb) })
    }

    /** Uninstall (or, for system apps, disable) an app in the current profile. */
    suspend fun uninstallApp(packageName: String): InstallResult {
        val wrapper = wrapper(packageName)
        val svc = requireService()
        return from(awaitInstall { cb -> svc.uninstallApp(wrapper, cb) })
    }

    suspend fun freeze(packageName: String) {
        val wrapper = wrapper(packageName)
        syncCall { it.freezeApp(wrapper) }
    }

    suspend fun unfreeze(packageName: String) {
        val wrapper = wrapper(packageName)
        syncCall { it.unfreezeApp(wrapper) }
    }

    // ------------------------------------------------------------------ cross-profile widgets / packages

    suspend fun getCrossProfileWidgetProviders(): List<String> =
        syncCall { it.crossProfileWidgetProviders }

    suspend fun setCrossProfileWidgetProviderEnabled(packageName: String, enabled: Boolean): Boolean =
        syncCall { it.setCrossProfileWidgetProviderEnabled(packageName, enabled) }

    suspend fun getCrossProfilePackages(): List<String> =
        syncCall { it.crossProfilePackages }

    suspend fun setCrossProfilePackages(packages: List<String>) {
        syncCall { it.setCrossProfilePackages(packages) }
    }

    // ------------------------------------------------------------------ permission / service control

    suspend fun hasUsageStatsPermission(): Boolean = syncCall { it.hasUsageStatsPermission() }
    suspend fun hasSystemAlertPermission(): Boolean = syncCall { it.hasSystemAlertPermission() }
    suspend fun hasAllFileAccessPermission(): Boolean = syncCall { it.hasAllFileAccessPermission() }

    suspend fun stopShelterService(kill: Boolean) = syncCall { it.stopShelterService(kill) }
    suspend fun ping() = syncCall { it.ping(); Unit }

    // ------------------------------------------------------------------ helpers

    /** Current service binder, or fail fast when the repository is not configured yet. */
    private fun requireService(): IShelterService =
        service ?: throw ServiceUnavailableException(
            IllegalStateException("ShelterService not configured")
        )

    private fun wrapper(packageName: String): ApplicationInfoWrapper =
        cache[packageName] ?: throw AppNotFoundException(packageName)

    private suspend fun awaitApps(showAll: Boolean): List<ApplicationInfoWrapper> {
        val svc = requireService()
        return suspendCancellableCoroutine { cont ->
            val guard = AtomicBoolean(false)
            val callback = object : IGetAppsCallback.Stub() {
                override fun callback(apps: List<ApplicationInfoWrapper>) {
                    if (!guard.compareAndSet(false, true)) return
                    if (cont.isActive) cont.resume(apps)
                }
            }
            cont.invokeOnCancellation { guard.set(true) }
            try {
                svc.getApps(callback, showAll)
            } catch (e: RemoteException) {
                if (guard.compareAndSet(false, true)) {
                    if (cont.isActive) cont.resumeWithException(ServiceUnavailableException(e))
                }
            }
        }
    }

    private suspend fun awaitIcon(wrapper: ApplicationInfoWrapper): Bitmap {
        val svc = requireService()
        return suspendCancellableCoroutine { cont ->
            val guard = AtomicBoolean(false)
            val callback = object : ILoadIconCallback.Stub() {
                override fun callback(icon: Bitmap) {
                    if (!guard.compareAndSet(false, true)) return
                    if (cont.isActive) cont.resume(icon)
                }
            }
            cont.invokeOnCancellation { guard.set(true) }
            try {
                svc.loadIcon(wrapper, callback)
            } catch (e: RemoteException) {
                if (guard.compareAndSet(false, true)) {
                    if (cont.isActive) cont.resumeWithException(ServiceUnavailableException(e))
                }
            }
        }
    }

    private suspend fun awaitInstall(launch: (IAppInstallCallback) -> Unit): Int {
        requireService()
        return suspendCancellableCoroutine { cont ->
            val guard = AtomicBoolean(false)
            val callback = object : IAppInstallCallback.Stub() {
                override fun callback(result: Int) {
                    if (!guard.compareAndSet(false, true)) return
                    if (cont.isActive) cont.resume(result)
                }
            }
            cont.invokeOnCancellation { guard.set(true) }
            try {
                launch(callback)
            } catch (e: RemoteException) {
                if (guard.compareAndSet(false, true)) {
                    if (cont.isActive) cont.resumeWithException(ServiceUnavailableException(e))
                }
            }
        }
    }

    private suspend fun <T> syncCall(block: (IShelterService) -> T): T =
        withContext(Dispatchers.IO) {
            val svc = requireService()
            try {
                block(svc)
            } catch (e: RemoteException) {
                throw ServiceUnavailableException(e)
            }
        }

    private fun ApplicationInfoWrapper.toAppInfo(autoFreeze: Set<String>): AppInfo = AppInfo(
        packageName = packageName,
        label = label ?: packageName,
        icon = null,
        isSystem = isSystem,
        isHidden = isHidden,
        isInAutoFreezeList = autoFreeze.contains(packageName),
    )

    private companion object {
        const val RESULT_CANNOT_INSTALL_SYSTEM_APP = 100001

        fun from(code: Int): InstallResult = when (code) {
            Activity.RESULT_OK -> InstallResult.Ok
            RESULT_CANNOT_INSTALL_SYSTEM_APP -> InstallResult.CannotInstallSystemApp
            else -> InstallResult.Other(code)
        }
    }
}
