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
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import net.typeblog.shelter.R
import net.typeblog.shelter.profile.Actions
import net.typeblog.shelter.profile.CrossProfileAction
import net.typeblog.shelter.services.IFileShuttleService
import net.typeblog.shelter.services.IFileShuttleServiceCallback

/**
 * A document provider to show files across the profile boundary
 * in the system's Documents UI.
 *
 * This is an interface to FileShuttleService on the other profile. The
 * service handle is acquired synchronously per request (bounded wait) and
 * released when idle so the system can reclaim memory.
 */
class CrossProfileDocumentsProvider : DocumentsProvider() {

    companion object {
        // The dummy root path that will be replaced by the real path to
        // external storage on the other side.
        const val DUMMY_ROOT = "/shelter_storage_root/"

        private const val AUTHORITY = "net.typeblog.shelter.documents"

        // Upper bound for the synchronous service bind; FileShuttleService
        // itself idle-times out after this period.
        private const val SERVICE_TIMEOUT_MS = 10_000L

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            DocumentsContract.Root.COLUMN_ROOT_ID,
            DocumentsContract.Root.COLUMN_DOCUMENT_ID,
            DocumentsContract.Root.COLUMN_ICON,
            DocumentsContract.Root.COLUMN_TITLE,
            DocumentsContract.Root.COLUMN_FLAGS,
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }

    private var mService: IFileShuttleService? = null

    // Monotonic bind generation. A callback carrying a stale generation is a
    // late or abandoned result and is rejected.
    private var mBindGeneration = 0

    private val mHandler = Handler(Looper.getMainLooper())

    // Periodic task to release the handle to the service. Since this provider
    // may persist for a long time, we release the service when idle, enabling
    // the system to release memory.
    private val mReleaseServiceTask = Runnable { releaseService() }

    private val mLock = Object()

    private val mDeathRecipient = object : IBinder.DeathRecipient {
        override fun binderDied() {
            // The service process went away: drop the handle so the next
            // request re-binds. Tombstoned binder may still be referenced.
            synchronized(mLock) {
                mService = null
            }
            mHandler.removeCallbacks(mReleaseServiceTask)
        }
    }

    private fun doBindService() {
        synchronized(mLock) {
            if (mService != null) return

            val context = context ?: return
            val generation = ++mBindGeneration

            // Call DummyActivity on the other side to bind the service for us.
            val intent = Intent(Actions.START_FILE_SHUTTLE)
            val extra = Bundle()
            extra.putBinder("callback", object : IFileShuttleServiceCallback.Stub() {
                override fun callback(service: IFileShuttleService?) {
                    if (service == null) return
                    synchronized(mLock) {
                        // Late callback rejection: only accept the result of
                        // the most recent bind request.
                        if (generation != mBindGeneration) return
                        mService = service
                        try {
                            service.asBinder().linkToDeath(mDeathRecipient, 0)
                        } catch (e: RemoteException) {
                            // Binder already dead; will be re-acquired on demand.
                        }
                        mLock.notifyAll()
                    }
                    resetReleaseService()
                }
            })
            intent.putExtra("extra", extra)
            // One-time transaction identity for this acquisition attempt. The
            // receiving DummyActivity only hands out the FileShuttleService
            // binder to a request carrying a fresh token on a steady-state
            // HMAC-signed intent; being a String extra, the token is covered by
            // the intent signature, so a foreign caller cannot substitute it.
            intent.putExtra(
                CrossProfileAction.EXTRA_CALLBACK_TOKEN,
                java.util.UUID.randomUUID().toString(),
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                intent.transferIntentToProfile(context)
            } catch (e: IllegalStateException) {
                // Try with the other action: we use a distinct intent for
                // parent -> profile and profile -> parent to avoid the action
                // chooser dialog, so try the other if one is not found.
                intent.action = Actions.START_FILE_SHUTTLE_2
                intent.transferIntentToProfile(context)
            }
            try {
                context.startActivity(intent)
            } catch (e: Exception) {
                // Cannot route the bind request; nothing will call back.
                mBindGeneration++
                return
            }

            // A hack to convert the asynchronous process of starting the
            // service to synchronous, bounded by a deadline.
            try {
                mLock.wait(SERVICE_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                // Interrupted; treat as bind failure.
            }

            if (mService == null) {
                // Timeout: abandon this request and reject any late callback.
                mBindGeneration++
            }
        }
    }

    private fun ensureServiceBound() {
        if (mService == null) {
            doBindService()
        } else {
            try {
                mService!!.ping()
                resetReleaseService()
            } catch (e: RemoteException) {
                synchronized(mLock) {
                    mService = null
                }
                doBindService()
            }
        }
    }

    private fun releaseService() {
        synchronized(mLock) {
            try {
                mService?.asBinder()?.unlinkToDeath(mDeathRecipient, 0)
            } catch (e: Exception) {
                // Already dying; nothing to clean up.
            }
            mService = null
        }
    }

    private fun resetReleaseService() {
        mHandler.removeCallbacks(mReleaseServiceTask)
        mHandler.postDelayed(mReleaseServiceTask, SERVICE_TIMEOUT_MS / 2)
    }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<String>?): Cursor {
        val result = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val row = result.newRow()
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, DUMMY_ROOT)
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, DUMMY_ROOT)
        row.add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher_egg)
        row.add(
            DocumentsContract.Root.COLUMN_TITLE,
            if (context!!.isProfileOwner())
                context!!.getString(R.string.fragment_profile_main)
            else
                context!!.getString(R.string.fragment_profile_work)
        )
        // SUPPORTS_IS_CHILD is required for OPEN_DOCUMENT_TREE.
        row.add(
            DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_SUPPORTS_CREATE or
                DocumentsContract.Root.FLAG_LOCAL_ONLY or
                DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD
        )
        return result
    }

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor? {
        ensureServiceBound()
        val service = mService ?: return null
        return try {
            val fileInfo = service.loadFileMeta(documentId)
            val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
            includeFile(result, fileInfo?.mapKeys { it.key.toString() })
            result
        } catch (e: RemoteException) {
            null
        }
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String
    ): Cursor? {
        ensureServiceBound()
        val service = mService ?: return null
        return try {
            val files = service.loadFiles(parentDocumentId)
            val result = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
            // Allow receiving notification on create / delete.
            result.setNotificationUri(
                context!!.contentResolver,
                DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId)
            )

            for (file in files.orEmpty()) {
                @Suppress("UNCHECKED_CAST")
                includeFile(result, file as Map<String, Any?>?)
            }
            result
        } catch (e: RemoteException) {
            null
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        ensureServiceBound()
        val service = mService ?: return null
        return try {
            service.openFile(documentId, mode)
        } catch (e: RemoteException) {
            null
        }
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor? {
        ensureServiceBound()
        val service = mService ?: return null
        return try {
            val fd = service.openThumbnail(documentId, sizeHint) ?: return null
            AssetFileDescriptor(fd, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
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
        val service = mService ?: return null
        return try {
            val ret = service.createFile(parentDocumentId, mimeType, displayName)
            context!!.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(AUTHORITY, parentDocumentId), null
            )
            ret
        } catch (e: RemoteException) {
            null
        }
    }

    override fun deleteDocument(documentId: String) {
        ensureServiceBound()
        val service = mService ?: return
        try {
            val parent = service.deleteFile(documentId)
            context!!.contentResolver.notifyChange(
                DocumentsContract.buildDocumentUri(AUTHORITY, parent), null
            )
        } catch (e: RemoteException) {
            // Ignore; nothing to notify.
        }
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        ensureServiceBound()
        val service = mService ?: return false
        return try {
            service.isChildOf(parentDocumentId, documentId)
        } catch (e: RemoteException) {
            false
        }
    }

    private fun includeFile(cursor: MatrixCursor, fileInfo: Map<String, Any?>?) {
        val row = cursor.newRow()
        for (col in DEFAULT_DOCUMENT_PROJECTION) {
            row.add(col, fileInfo?.get(col))
        }
    }
}