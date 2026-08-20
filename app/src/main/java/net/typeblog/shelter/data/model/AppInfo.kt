package net.typeblog.shelter.data.model

import android.graphics.drawable.Drawable

/**
 * UI-facing app info. The AIDL layer still uses ApplicationInfoWrapper (Parcelable).
 * This is the domain model consumed by ViewModels and Compose screens.
 */
data class AppInfo(
    val packageName: String,
    val label: String,
    val icon: Drawable? = null,
    val isSystem: Boolean = false,
    val isHidden: Boolean = false, // frozen
    val isInAutoFreezeList: Boolean = false,
)
