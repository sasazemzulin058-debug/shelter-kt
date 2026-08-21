package net.typeblog.shelter.profile

/**
 * All cross-profile intent actions.
 * These strings MUST match the intent-filter declarations in AndroidManifest.xml
 * and the CrossProfileIntentFilter registrations in ProfileManager.
 */
object Actions {
    private const val PREFIX = "net.typeblog.shelter.action."

    const val START_SERVICE = "${PREFIX}START_SERVICE"
    const val TRY_START_SERVICE = "${PREFIX}TRY_START_SERVICE"
    const val INSTALL_PACKAGE = "${PREFIX}INSTALL_PACKAGE"
    const val UNINSTALL_PACKAGE = "${PREFIX}UNINSTALL_PACKAGE"
    const val FINALIZE_PROVISION = "${PREFIX}FINALIZE_PROVISION"
    const val UNFREEZE_AND_LAUNCH = "${PREFIX}UNFREEZE_AND_LAUNCH"
    const val PUBLIC_FREEZE_ALL = "${PREFIX}PUBLIC_FREEZE_ALL"
    const val PUBLIC_UNFREEZE_AND_LAUNCH = "${PREFIX}PUBLIC_UNFREEZE_AND_LAUNCH"
    const val FREEZE_ALL_IN_LIST = "${PREFIX}FREEZE_ALL_IN_LIST"
    const val START_FILE_SHUTTLE = "${PREFIX}START_FILE_SHUTTLE"
    const val START_FILE_SHUTTLE_2 = "${PREFIX}START_FILE_SHUTTLE_2"
    const val SYNCHRONIZE_PREFERENCE = "${PREFIX}SYNCHRONIZE_PREFERENCE"
    const val PACKAGEINSTALLER_CALLBACK = "${PREFIX}PACKAGEINSTALLER_CALLBACK"

    // Setup actions use the plain package namespace, not the ".action." prefix.
    const val ACTION_RESUME_SETUP = "net.typeblog.shelter.RESUME_SETUP"
    const val ACTION_PROFILE_PROVISIONED = "net.typeblog.shelter.PROFILE_PROVISIONED"

    /** Actions that don't require signature verification (public entry points). */
    val UNSIGNED_ACTIONS = setOf(
        PUBLIC_FREEZE_ALL,
        PUBLIC_UNFREEZE_AND_LAUNCH,
    )

    /**
     * Actions only callable from within the same process, using the one-shot
     * short-window token bypass (see AuthManager.registerSameProcess).
     */
    val SAME_PROCESS_ACTIONS = setOf(
        INSTALL_PACKAGE,
        UNINSTALL_PACKAGE,
        UNFREEZE_AND_LAUNCH,
    )
}
