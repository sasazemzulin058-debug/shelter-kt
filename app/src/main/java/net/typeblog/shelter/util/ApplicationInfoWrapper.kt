package net.typeblog.shelter.util

import android.annotation.TargetApi
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.os.Parcelable

class ApplicationInfoWrapper : Parcelable {
    private var mInfo: ApplicationInfo? = null
    private var mLabel: String? = null
    private var mIsHidden = false

    private constructor()

    constructor(info: ApplicationInfo) {
        mInfo = info
    }

    fun loadLabel(pm: PackageManager): ApplicationInfoWrapper {
        mLabel = pm.getApplicationLabel(mInfo!!).toString()
        return this
    }

    // Only used from ShelterService
    fun setHidden(hidden: Boolean): ApplicationInfoWrapper {
        mIsHidden = hidden
        return this
    }

    val packageName: String
        get() = mInfo!!.packageName

    val label: String?
        get() = mLabel

    val sourceDir: String
        get() = mInfo!!.sourceDir

    val splitApks: Array<String>?
        @TargetApi(Build.VERSION_CODES.O)
        get() = mInfo!!.splitSourceDirs

    // NOTE: This does not relate to the "freezing" feature in Shelter
    val enabled: Boolean
        get() = mInfo!!.enabled

    val isHidden: Boolean
        get() = mIsHidden

    val info: ApplicationInfo?
        get() = mInfo

    val isSystem: Boolean
        get() = (mInfo!!.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeParcelable(mInfo, flags)
        dest.writeString(mLabel)
        dest.writeByte((if (mIsHidden) 1 else 0).toByte())
    }

    override fun describeContents(): Int = mInfo!!.packageName.hashCode()

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ApplicationInfoWrapper> =
            object : Parcelable.Creator<ApplicationInfoWrapper> {
                override fun newArray(size: Int): Array<ApplicationInfoWrapper?> = arrayOfNulls(size)

                override fun createFromParcel(source: Parcel): ApplicationInfoWrapper {
                    val info = ApplicationInfoWrapper()
                    info.mInfo = source.readParcelable(ApplicationInfo::class.java.classLoader)
                    info.mLabel = source.readString()
                    info.mIsHidden = source.readByte().toInt() != 0
                    return info
                }
            }
    }
}
