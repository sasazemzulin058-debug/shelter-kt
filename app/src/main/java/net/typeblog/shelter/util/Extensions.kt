package net.typeblog.shelter.util

import android.app.Notification
import android.content.Context
import android.content.Intent

/**
 * Focused Kotlin extensions split out of the original monolithic Utility
 * class. Each extension delegates to the matching [Utility] helper (which
 * remains the canonical legacy surface) and is consumed by the util/receiver
 * slice: the documents provider and the device-admin receiver.
 */

/** True when this app is the owner of the current profile. */
fun Context.isProfileOwner(): Boolean = Utility.isProfileOwner(this)

/** Route [this] intent to the other profile (and sign it for transfer). */
fun Intent.transferIntentToProfile(context: Context) {
    Utility.transferIntentToProfile(context, this)
}

/** Build a cross-version compatible notification through [Utility]. */
fun Context.buildNotification(
    important: Boolean,
    ticker: String,
    title: String,
    desc: String,
    icon: Int,
): Notification = Utility.buildNotification(this, important, ticker, title, desc, icon)