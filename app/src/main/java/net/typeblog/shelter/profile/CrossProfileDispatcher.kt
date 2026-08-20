package net.typeblog.shelter.profile

import android.content.Intent
import net.typeblog.shelter.data.settings.SettingsStore

/**
 * Routes an authenticated DummyActivity intent to its handler.
 * Keeps DummyActivity thin: it owns the authentication gate only; the actual
 * work for every action lives in [CrossProfileAction].
 */
object CrossProfileDispatcher {

    fun dispatch(activity: DummyActivity, intent: Intent, settings: SettingsStore) {
        when (intent.action) {
            Actions.START_SERVICE -> CrossProfileAction.startService(activity)
            Actions.TRY_START_SERVICE -> CrossProfileAction.tryStartService(activity)
            Actions.INSTALL_PACKAGE -> CrossProfileAction.installPackage(activity)
            Actions.UNINSTALL_PACKAGE -> CrossProfileAction.uninstallPackage(activity)
            Actions.FINALIZE_PROVISION -> CrossProfileAction.finalizeProvision(activity, settings)
            Actions.UNFREEZE_AND_LAUNCH, Actions.PUBLIC_UNFREEZE_AND_LAUNCH ->
                CrossProfileAction.unfreezeAndLaunch(activity, settings)
            Actions.PUBLIC_FREEZE_ALL -> CrossProfileAction.publicFreezeAll(activity, settings)
            Actions.FREEZE_ALL_IN_LIST -> CrossProfileAction.freezeAllInList(activity)
            Actions.START_FILE_SHUTTLE, Actions.START_FILE_SHUTTLE_2 ->
                CrossProfileAction.startFileShuttle(activity)
            Actions.SYNCHRONIZE_PREFERENCE ->
                CrossProfileAction.synchronizePreference(activity, settings)
            else -> activity.finish()
        }
    }
}
