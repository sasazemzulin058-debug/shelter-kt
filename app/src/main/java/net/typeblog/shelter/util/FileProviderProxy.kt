package net.typeblog.shelter.util

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider
import java.io.FileNotFoundException

// A simple and naive FileProvider which forwards content Uris to a given Uri
// from another profile through UriForwardProxy. This class can, for now, only
// forward one Uri at a time: it forwards all requests to one Uri assigned
// through the static methods.
class FileProviderProxy : FileProvider() {
    companion object {
        private const val AUTHORITY_NAME = "net.typeblog.shelter.files"
        // All content Uris pointing to this path indicate a forwarded Fd.
        private const val FORWARD_PATH_PREFIX = "/forward/"

        private var sProxy: UriForwardProxy? = null

        // Register the Uri to be forwarded. Returns an automatically generated
        // temporary Uri for this request.
        @JvmStatic
        fun setUriForwardProxy(proxy: UriForwardProxy, suffix: String): Uri {
            sProxy = proxy
            return Uri.parse("content://$AUTHORITY_NAME${FORWARD_PATH_PREFIX}temp.$suffix")
        }

        // Close and delete the current forwarder.
        @JvmStatic
        fun clearForwardProxy() {
            sProxy = null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (uri.path.orEmpty().startsWith(FORWARD_PATH_PREFIX) && sProxy != null) {
            // If we are now inside FORWARD_PATH_PREFIX, forward the request to
            // the UriForwardProxy. Returns null on failure, matching the remote
            // open semantics.
            return sProxy!!.open(mode) ?: throw FileNotFoundException("Forwarded URI could not be opened")
        }
        return super.openFile(uri, mode)
    }
}
