package net.typeblog.shelter.services

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Point
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import net.typeblog.shelter.R
import net.typeblog.shelter.util.CrossProfileDocumentsProvider
import net.typeblog.shelter.util.Utility
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.Serializable

// A service to forward file information across the profile boundary.
class FileShuttleService : Service() {

    companion object {
        const val TIMEOUT = 10_000L
    }

    // Bounded thumbnail pipe writes run off these threads; the service-owned scope
    // is cancelled with the service and never leaks a writer past its lifetime.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Periodic task to stop the service when idle. This service does not persist.
    private val mSuicideTask = Runnable { suicide() }
    private val mHandler = Handler(Looper.getMainLooper())

    private val mStub = object : IFileShuttleService.Stub() {
        override fun ping() {
            resetSuicideTask()
        }

        override fun loadFiles(path: String?): List<Map<String, Serializable>> {
            resetSuicideTask()
            val ret = ArrayList<Map<String, Serializable>>()
            val f = resolvePath(path ?: return ret) ?: return ret
            f.listFiles()?.let { children ->
                for (child in children) {
                    ret.add(loadFileMeta(child.absolutePath))
                }
            }
            return ret
        }

        override fun loadFileMeta(path: String): Map<String, Serializable> {
            resetSuicideTask()
            val f = resolvePath(path) ?: return emptyMap()
            val map = HashMap<String, Serializable>()
            map[DocumentsContract.Document.COLUMN_DOCUMENT_ID] = f.absolutePath
            if (f == rootFile) {
                // Show "Shelter" as the name of the root directory.
                map[DocumentsContract.Document.COLUMN_DISPLAY_NAME] = getString(R.string.app_name)
            } else {
                map[DocumentsContract.Document.COLUMN_DISPLAY_NAME] = f.name
            }
            map[DocumentsContract.Document.COLUMN_SIZE] = f.length()
            map[DocumentsContract.Document.COLUMN_LAST_MODIFIED] = f.lastModified()

            if (f.isDirectory) {
                map[DocumentsContract.Document.COLUMN_MIME_TYPE] =
                    DocumentsContract.Document.MIME_TYPE_DIR
                map[DocumentsContract.Document.COLUMN_FLAGS] =
                    DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE or
                        DocumentsContract.Document.FLAG_SUPPORTS_DELETE
            } else {
                var mime = MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(Utility.getFileExtension(f.absolutePath))
                var flags = DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                if (mime != null && (mime.startsWith("image/") || mime.startsWith("video/"))) {
                    flags = flags or DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
                }
                if (mime == null) {
                    mime = "application/unknown"
                }
                map[DocumentsContract.Document.COLUMN_MIME_TYPE] = mime
                map[DocumentsContract.Document.COLUMN_FLAGS] = flags
            }
            return map
        }

        override fun openFile(path: String, mode: String): ParcelFileDescriptor? {
            resetSuicideTask()
            val f = resolvePath(path) ?: return null
            val numericMode = ParcelFileDescriptor.parseMode(mode)

            return try {
                if ((numericMode and ParcelFileDescriptor.MODE_WRITE_ONLY) != 0) {
                    // When the file is opened in writable mode and it is a media type,
                    // notify the media scanner of the update after writing finishes.
                    ParcelFileDescriptor.open(
                        f, numericMode, mHandler
                    ) {
                        val mime = MimeTypeMap.getSingleton()
                            .getMimeTypeFromExtension(Utility.getFileExtension(f.absolutePath))
                        notifyMediaScannerIfNecessary(f, mime)
                    }
                } else {
                    ParcelFileDescriptor.open(f, numericMode)
                }
            } catch (e: IOException) {
                null
            }
        }

        override fun openThumbnail(path: String, sizeHint: Point): ParcelFileDescriptor? {
            resetSuicideTask()
            val fullPath = resolvePath(path) ?: return null
            val absPath = fullPath.absolutePath
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(Utility.getFileExtension(absPath)) ?: return null
            return when {
                mime.startsWith("image/") -> loadImageThumbnail(absPath, sizeHint)
                mime.startsWith("video/") -> loadVideoThumbnail(absPath)
                else -> null
            }
        }

        override fun createFile(path: String, mimeType: String, displayName: String): String? {
            resetSuicideTask()
            val isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == mimeType
            val shouldAppendExtension = mimeType != null && !isDirectory &&
                mimeType != "application/octet-stream"

            var fullPath = "$path/$displayName"
            // Append extension for files if a MIME type is specified.
            if (shouldAppendExtension) {
                val extensionPart = "." + MimeTypeMap.getSingleton()
                    .getExtensionFromMimeType(mimeType)
                if (!fullPath.endsWith(extensionPart)) {
                    fullPath += extensionPart
                }
            }

            val f = resolvePath(fullPath) ?: return null
            return try {
                if ((isDirectory && !f.mkdir()) || (!isDirectory && !f.createNewFile())) {
                    null
                } else {
                    notifyMediaScannerIfNecessary(f, mimeType)
                    f.absolutePath
                }
            } catch (e: IOException) {
                null
            }
        }

        override fun deleteFile(path: String): String? {
            resetSuicideTask()
            val f = resolvePath(path) ?: return null
            f.delete()
            return f.parentFile?.absolutePath
        }

        override fun isChildOf(parent: String, child: String): Boolean {
            val parentFile = resolvePath(parent) ?: return false
            val childFile = resolvePath(child) ?: return false
            var parentPath = parentFile.absolutePath
            if (parentPath.lastOrNull() != File.separatorChar) {
                parentPath += File.separatorChar // Make sure it ends with separator.
            }
            return parentFile.exists() && parentFile.isDirectory &&
                childFile.exists() && childFile.absolutePath.startsWith(parentPath)
        }
    }

    override fun onBind(intent: Intent): IBinder {
        resetSuicideTask()
        return mStub
    }

    override fun onDestroy() {
        super.onDestroy()
        mHandler.removeCallbacks(mSuicideTask)
        scope.cancel()
        Log.d("FileShuttleService", "being destroyed")
    }

    // Canonical external-storage root, resolved once; everything the shuttle
    // serves must live under it.
    private val rootFile: File? by lazy {
        runCatching { Environment.getExternalStorageDirectory().canonicalFile }.getOrNull()
    }

    /**
     * Resolve a client-supplied path and confine it to external storage.
     * [CrossProfileDocumentsProvider.DUMMY_ROOT]-prefixed paths map onto the external
     * storage root; everything else is used as-is. A canonical-path check rejects any
     * traversal (`..`) that escapes the external storage directory; returns null for
     * paths that cannot be confined.
     */
    private fun resolvePath(path: String): File? {
        val raw = if (path.startsWith(CrossProfileDocumentsProvider.DUMMY_ROOT)) {
            path.replaceFirst(
                CrossProfileDocumentsProvider.DUMMY_ROOT,
                Environment.getExternalStorageDirectory().absolutePath
            )
        } else {
            path
        }
        val root = rootFile ?: return null
        val file = runCatching { File(raw).canonicalFile }.getOrNull() ?: return null
        val rootPath = root.absolutePath
        val filePath = file.absolutePath
        return if (filePath == rootPath || filePath.startsWith("$rootPath${File.separator}")) {
            file
        } else {
            null
        }
    }

    private fun resetSuicideTask() {
        mHandler.removeCallbacks(mSuicideTask)
        mHandler.postDelayed(mSuicideTask, TIMEOUT)
    }

    private fun suicide() {
        mHandler.removeCallbacks(mSuicideTask)
        // The binder connection is owned by whoever bound us; stopping ourselves
        // is enough — the binding side will notice the service going away.
        stopSelf()
    }

    private fun loadImageThumbnail(fullPath: String, sizeHint: Point): ParcelFileDescriptor? {
        val id = Utility.getMediaStoreId(this, fullPath)
        if (id == -1) {
            // Fallback: directly load a scaled bitmap from the file.
            return loadBitmapThumbnail(fullPath, sizeHint)
        }
        var result = MediaStore.Images.Thumbnails.queryMiniThumbnail(
            contentResolver, id.toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null
        )
        if (result.count == 0) {
            // If no thumbnail is found, request one first.
            MediaStore.Images.Thumbnails.getThumbnail(
                contentResolver, id.toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null
            )
            result.close()
            result = MediaStore.Images.Thumbnails.queryMiniThumbnail(
                contentResolver, id.toLong(), MediaStore.Images.Thumbnails.MINI_KIND, null
            )
        }
        result.use { cursor ->
            if (cursor.count == 0) {
                // Fallback: directly load a scaled bitmap from the file.
                return loadBitmapThumbnail(fullPath, sizeHint)
            }
            cursor.moveToFirst()
            return try {
                val index = cursor.getColumnIndex(MediaStore.Images.Thumbnails.DATA)
                contentResolver.openFileDescriptor(
                    Uri.fromFile(File(cursor.getString(index))), "r"
                )
            } catch (e: FileNotFoundException) {
                null
            }
        }
    }

    private fun loadVideoThumbnail(fullPath: String): ParcelFileDescriptor? {
        // The MediaStore interface for video thumbnails does not work at all and
        // cannot even retrieve video IDs from the database.
        val bmp = ThumbnailUtils.createVideoThumbnail(
            fullPath, MediaStore.Video.Thumbnails.MINI_KIND
        )
        return bitmapToFd(bmp)
    }

    // Fallback: load a scaled-down bitmap straight from disk.
    private fun loadBitmapThumbnail(path: String, sizeHint: Point): ParcelFileDescriptor? {
        val bmp = Utility.decodeSampledBitmap(path, sizeHint.x, sizeHint.y) ?: return null
        return bitmapToFd(bmp)
    }

    private fun bitmapToFd(bmp: Bitmap?): ParcelFileDescriptor? {
        if (bmp == null) return null
        val pair = try {
            // Use a pipe as a virtual in-memory ParcelFileDescriptor.
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            return null
        }

        // Send the bitmap into the pipe on a worker so we can return the reading fd
        // to the Documents UI before the Bitmap has been fully written.
        scope.launch {
            try {
                FileOutputStream(pair[1].fileDescriptor).use { os ->
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, os)
                    os.flush()
                }
            } catch (e: IOException) {
                // The reader side already closed; nothing to do.
            } finally {
                pair[1].close()
            }
        }

        return pair[0]
    }

    private fun notifyMediaScannerIfNecessary(f: File, mimeType: String?) {
        // Notify the media scanner to scan the file. This has to be done AFTER
        // file creation.
        if (mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("video/"))) {
            val intent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            intent.data = Uri.fromFile(f)
            sendBroadcast(intent)
        }
    }
}
