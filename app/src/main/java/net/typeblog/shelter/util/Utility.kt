package net.typeblog.shelter.util

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.FileDescriptor
import android.provider.MediaStore
import android.widget.Toast
import net.typeblog.shelter.R
import net.typeblog.shelter.profile.AuthManager
import net.typeblog.shelter.profile.ProfileManager
import net.typeblog.shelter.services.IShelterService

/**
 * Focused legacy utility surface. Only the helpers still referenced by the
 * current core live here; the rest (profile policy, etc.) moved to
 * [ProfileManager] and domain classes.
 */
object Utility {

    fun isProfileOwner(context: Context): Boolean = ProfileManager.isProfileOwner(context)

    fun transferIntentToProfileUnsigned(context: Context, intent: Intent) {
        ProfileManager.transferIntentToProfileUnsigned(context, intent)
    }

    fun transferIntentToProfile(context: Context, intent: Intent) {
        transferIntentToProfileUnsigned(context, intent)
        // AuthManager is stateless aside from prefs/keystore; a local instance is fine
        // for the one-shot signing helper used by non-Hilt callers.
        AuthManager(context).signIntent(intent)
    }

    fun killShelterServices(serviceMain: IShelterService?, serviceWork: IShelterService?) {
        try {
            serviceWork?.stopShelterService(true)
        } catch (_: Exception) {
            // We are stopping anyway.
        }
        try {
            serviceMain?.stopShelterService(false)
        } catch (_: Exception) {
            // We are stopping anyway.
        }
    }

    fun createLauncherShortcut(context: Context, launchIntent: Intent, icon: Icon, id: String, label: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val sm = context.getSystemService(ShortcutManager::class.java)
            if (sm.isRequestPinShortcutSupported) {
                val info = ShortcutInfo.Builder(context, id)
                    .setIntent(launchIntent)
                    .setIcon(icon)
                    .setShortLabel(label)
                    .setLongLabel(label)
                    .build()
                val addIntent = sm.createShortcutResultIntent(info)
                sm.requestPinShortcut(
                    info,
                    PendingIntent.getBroadcast(
                        context, 0, addIntent,
                        PendingIntent.FLAG_IMMUTABLE
                    ).intentSender
                )
            } else {
                Toast.makeText(context, R.string.unsupported_launcher, Toast.LENGTH_LONG).show()
            }
        } else {
            val shortcutIntent = Intent("com.android.launcher.action.INSTALL_SHORTCUT").apply {
                putExtra(Intent.EXTRA_SHORTCUT_INTENT, launchIntent)
                putExtra(Intent.EXTRA_SHORTCUT_NAME, label)
                putExtra(Intent.EXTRA_SHORTCUT_ICON, drawableToBitmap(icon.loadDrawable(context)))
            }
            context.sendBroadcast(shortcutIntent)
            Toast.makeText(context, R.string.shortcut_create_success, Toast.LENGTH_SHORT).show()
        }
    }

    fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) {
            return drawable.bitmap
        }
        val width = drawable.intrinsicWidth.coerceAtLeast(1)
        val height = drawable.intrinsicHeight.coerceAtLeast(1)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            val canvas = Canvas(this)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
        }
    }

    fun checkUsageStatsPermission(context: Context): Boolean =
        checkSpecialAccessPermission(context, AppOpsManager.OPSTR_GET_USAGE_STATS)

    fun checkSystemAlertPermission(context: Context): Boolean =
        checkSpecialAccessPermission(context, AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW)

    fun checkAllFileAccessPermission(): Boolean = Environment.isExternalStorageManager()

    private fun checkSpecialAccessPermission(context: Context, name: String): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return appOps.checkOpNoThrow(name, android.os.Process.myUid(), context.packageName) ==
            AppOpsManager.MODE_ALLOWED
    }

    fun isMIUI(): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec("getprop ro.miui.ui.version.name")
            proc.inputStream.bufferedReader().use { it.readLine()?.trim()?.isNotEmpty() ?: false }
        } catch (_: Exception) {
            false
        }
    }

    fun getMediaStoreId(context: Context, path: String): Int {
        val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DATA} LIKE ?"
        context.contentResolver.query(uri, projection, selection, arrayOf(path), null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
            }
        }
        return -1
    }

    fun decodeSampledBitmap(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFile(filePath, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return android.graphics.BitmapFactory.decodeFile(filePath, options)
    }

    fun decodeSampledBitmap(fd: FileDescriptor, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeFileDescriptor(fd, null, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        return android.graphics.BitmapFactory.decodeFileDescriptor(fd, null, options)
    }

    private fun calculateInSampleSize(options: android.graphics.BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        val height = options.outHeight
        val width = options.outWidth
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
                halfHeight /= 2
                halfWidth /= 2
            }
        }
        return inSampleSize
    }

    fun getFileExtension(filePath: String): String? {
        val index = filePath.lastIndexOf('.')
        return if (index > 0) filePath.substring(index + 1) else null
    }

    fun buildNotification(context: Context, ticker: String, title: String, desc: String, icon: Int): Notification {
        return buildNotification(context, false, ticker, title, desc, icon)
    }

    fun buildNotification(
        context: Context,
        important: Boolean,
        ticker: String,
        title: String,
        desc: String,
        icon: Int,
    ): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildNotificationOreo(context, important, ticker, title, desc, icon)
        } else {
            buildNotificationLollipop(context, important, ticker, title, desc, icon)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotificationLollipop(
        context: Context,
        important: Boolean,
        ticker: String,
        title: String,
        desc: String,
        icon: Int,
    ): Notification {
        return Notification.Builder(context)
            .setTicker(ticker)
            .setContentTitle(title)
            .setContentText(desc)
            .setSmallIcon(icon)
            .setPriority(if (important) Notification.PRIORITY_MAX else Notification.PRIORITY_MIN)
            .build()
    }

    private fun buildNotificationOreo(
        context: Context,
        important: Boolean,
        ticker: String,
        title: String,
        desc: String,
        icon: Int,
    ): Notification {
        val id = if (important) NOTIFICATION_CHANNEL_IMPORTANT else NOTIFICATION_CHANNEL_ID
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(
                id,
                if (important) context.getString(R.string.notifications_important) else context.getString(R.string.app_name),
                if (important) NotificationManager.IMPORTANCE_HIGH else NotificationManager.IMPORTANCE_MIN,
            )
            nm.createNotificationChannel(channel)
        }
        nm.getNotificationChannel(id)?.apply {
            if (!important) {
                enableVibration(false)
                enableLights(false)
                importance = NotificationManager.IMPORTANCE_MIN
            } else {
                enableVibration(true)
                importance = NotificationManager.IMPORTANCE_HIGH
            }
            nm.createNotificationChannel(this)
        }
        return Notification.Builder(context, id)
            .setTicker(ticker)
            .setContentTitle(title)
            .setContentText(desc)
            .setSmallIcon(icon)
            .build()
    }

    private const val NOTIFICATION_CHANNEL_ID = "ShelterService"
    private const val NOTIFICATION_CHANNEL_IMPORTANT = "ShelterService-Important"

    fun openUrl(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}
