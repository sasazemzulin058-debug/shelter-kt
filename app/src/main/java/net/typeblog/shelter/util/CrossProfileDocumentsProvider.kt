package net.typeblog.shelter.util

import android.content.Intent
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import net.typeblog.shelter.R
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.services.FileShuttleService
import net.typeblog.shelter.services.IFileShuttleService
import net.typeblog.shelter.services.IFileShuttleServiceCallback
import java.io.Serializable

// A document provider to show files across the profile boundary
// in the system's Documents UI.
// This is an interface to FileShuttleService.
class CrossProfileDocumentsProvider : DocumentsProvider() {
    // The dummy root path that will be replaced by the real path to external
    // storage on the other side.
    companion object {
        const val DUMMY_ROOT = "/shelter_storage_root/"
        private const val AUTHORITY = "net.typeblog.shelter.documents"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        // How long we are willing to block a provider call waiting for the
        // in-flight bind to the other profile's FileShuttleService.
        private const val BIND_TIMEOUT_MS = FileShuttleService.TIMEOUT
    }

    private val mLock = Object()

    private val appContext: android.content.Context
        get() = context ?: throw IllegalStateException("Provider not attached")

    // Guarded by mLock.
    private var mService: IFileShuttleService? = null
    private var mBoundBinder: IBinder? = null
    private var mInFlight = false
    private var mInFlightRequestId = -1L
    private var mNextRequestId = 0L

    private val mHandler = Handler(Looper.getMainLooper())
    private val mReleaseServiceTask = Runnable { releaseService() }

    private val mDeathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            synchronized(mLock) {
                // The remote service died; drop the dead reference and wake
                // any waiter so it can observe the failure.
                mService = null
                mBoundBinder = null
                mInFlight = false
                mLock.notifyAll()
            }
        }
    }

    // Delegate binding to DummyActivity on the other profile, which binds the
    // FileShuttleService and hands us the binder through the callback. Only one
    // in-flight acquisition is allowed at a time; the rest of the callers block
    // on the same lock until the deadline.
    private fun startBind(requestId: Long) {
        val callback = object : IFileShuttleServiceCallback.Stub() {
            override fun callback(service: IFileShuttleService?) {
                synchronized(mLock) {
                    // Reject a callback that arrives after we have already
                    // timed out (or after a newer request superseded this one).
                    if (!mInFlight || requestId != mInFlightRequestId) return
                    val svc = service ?: return
                    try {
                        svc.asBinder().linkToDeath(mDeathRecipient, 0)
                    } catch (e: RemoteException) {
                        return
                    }
                    mService = svc
                    mBoundBinder = svc.asBinder()
                    mInFlight = false
                    mLock.notifyAll()
                }
            }
        }

        val intent = Intent(Actions.START_FILE_SHUTTLE)
        val extra = Bundle()
        extra.putBinder("callback", callback)
        intent.putExtra("extra", extra)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            Utility.transferIntentToProfile(appContext, intent)
        } catch (e: IllegalStateException) {
            // Distinct intents for parent -> profile and profile -> parent avoid
            // the action chooser; if one direction is not registered, try the other.
            intent.action = Actions.START_FILE_SHUTTLE_2
            Utility.transferIntentToProfile(appContext, intent)
        }
        appContext.startActivity(intent)
    }

    // Must be called with mLock held. Blocks until a service is ready, the
    // deadline expires, or the thread is interrupted.
    private fun bindWithDeadline() {
        if (mInFlight) {
            // A bind is already in progress; just wait for it.
            waitForService()
            return
        }
        val requestId = ++mNextRequestId
        mInFlight = true
        mInFlightRequestId = requestId
        startBind(requestId)
        waitForService()
    }

    // Must be called with mLock held.
    private fun waitForService() {
        val deadline = SystemClock.uptimeMillis() + BIND_TIMEOUT_MS
        while (mService == null) {
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0L) break
            try {
                mLock.wait(remaining)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (mService == null && mInFlight) {
            // Timed out. Invalidate the in-flight request so a late callback
            // cannot install a service for a request nobody is waiting on.
            mInFlight = false
            mInFlightRequestId = -1L
        }
    }

    private fun ensureServiceBound() {
        synchronized(mLock) {
            val current = mService
            if (current != null) {
                try {
                    current.ping()
                    resetReleaseService()
                    return
                } catch (e: RemoteException) {
                    releaseServiceLocked()
                }
            }
            bindWithDeadline()
            if (mService == null) {
                // Surface a typed failure the provider methods map to null/false.
                throw RemoteException("Unable to acquire file shuttle service")
            }
        }
    }

    private fun releaseService() {
        synchronized(mLock) {
            releaseServiceLocked()
        }
    }

    private fun releaseServiceLocked() {
        val binder = mBoundBinder
        if (binder != null) {
            try {
                binder.unlinkToDeath(mDeathRecipient, 0)
            } catch (e: RemoteException) {
                // Already dead; nothing to unlink.
            }
        }
        mService = null
        mBoundBinder = null
    }

    private fun resetReleaseService() {
        mHandler.removeCallbacks(mReleaseServiceTask)
        mHandler.postDelayed(mReleaseServiceTask, FileShuttleService.TIMEOUT / 2)
    }

    // Reject any document id that escapes DUMMY_ROOT or performs parent
    // traversal. The service resolves true external-storage paths; this is the
    // provider-side guard so a hostile document id never leaves the root.
    private fun validMode(mode: String): Boolean =
        mode == "r" || mode == "w" || mode == "rw"

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, DUMMY_ROOT)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DUMMY_ROOT)
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher_egg)
        row.add(
            DocumentsContract.Root.COLUMN_TITLE,
            if (Utility.isProfileOwner(appContext)) {
                appContext.getString(R.string.fragment_profile_main)
            } else {
                appContext.getString(R.string.fragment_profile_work)
            }
        )
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            // SUPPORTS_IS_CHILD is required for OPEN_DOCUMENT_TREE.
            DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                DocumentsContract.Root.FLAG_LOCAL_ONLY or
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
        )
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor? {
        ensureServiceBound()
        val fileInfo = try {
            val raw = mService?.loadFileMeta(documentId.sanitizeDocumentPath())
            if (raw != null) {
                val typed = HashMap<String, Any?>()
                for ((k, v) in raw) {
                    if (k is String) {
                        typed[k] = v
                    }
                }
                typed
            } else {
                null
            }
        } catch (e: RemoteException) {
            return null
        }
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        includeFile(result, fileInfo)
        return result
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        ensureServiceBound()
        val files = try {
            val rawList = mService?.loadFiles(parentDocumentId.sanitizeDocumentPath())
            val typedList = ArrayList<Map<String, Any?>>()
            if (rawList != null) {
                for (item in rawList) {
                    if (item is Map<*, *>) {
                        val typed = HashMap<String, Any?>()
                        for ((k, v) in item) {
                            if (k is String) {
                                typed[k] = v
                            }
                        }
                        typedList.add(typed)
                    }
                }
            }
            typedList
        } catch (e: RemoteException) {
            return null
        }
        val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        // Allow receiving notification on create / delete.
        result.setNotificationUri(
            appContext.contentResolver,
            DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId)
        )
        for (file in files) {
            includeFile(result, file)
        }
        return result
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        if (signal?.isCanceled == true || !validMode(mode)) return null
        ensureServiceBound()
        return try {
            mService?.openFile(documentId.sanitizeDocumentPath(), mode)
        } catch (e: RemoteException) {
            null
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?
    ): AssetFileDescriptor? {
        if (signal?.isCanceled == true) return null
        ensureServiceBound()
        return try {
            val pfd = mService?.openThumbnail(documentId.sanitizeDocumentPath(), sizeHint) ?: return null
            AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        } catch (e: RemoteException) {
            null
        }
    }

    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String? {
        ensureServiceBound()
        return try {
            val ret = mService?.createFile(
                parentDocumentId.sanitizeDocumentPath(),
                mimeType,
                displayName
            )
            appContext.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId),
                null
            )
            ret
        } catch (e: RemoteException) {
            null
        }
    }

    override fun deleteDocument(documentId: String) {
        ensureServiceBound()
        try {
            val parent = mService?.deleteFile(documentId.sanitizeDocumentPath())
            appContext.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(AUTHORITY, parent ?: DUMMY_ROOT),
                null
            )
        } catch (e: RemoteException) {
            // Ignore: the deletion simply did not go through.
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        ensureServiceBound()
        return try {
            mService?.isChildOf(
                parentDocumentId.sanitizeDocumentPath(),
                documentId.sanitizeDocumentPath()
            ) ?: false
        } catch (e: RemoteException) {
            false
        }
    }

    private fun includeFile(cursor: MatrixCursor, fileInfo: Map<String, Any?>?) {
        if (fileInfo == null) return
        val row = cursor.newRow()
        for (col in DEFAULT_DOCUMENT_PROJECTION) {
            row.add(col, fileInfo[col])
        }
    }
}
