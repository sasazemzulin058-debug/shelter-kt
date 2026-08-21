package net.typeblog.shelter.util

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.core.content.FileProvider

/**
 * A simple and naïve FileProvider which forwards content Uris to a given Uri
 * from another profile through [UriForwardProxy]. This class can, for now,
 * only forward one Uri each time: it forwards all requests to one Uri assigned
 * through static methods. The opener lifecycle is owned exclusively by the
 * originating side: [setUriForwardProxy] registers a fresh opener and
 * [clearForwardProxy] releases it once the terminal transaction is done.
 */
class FileProviderProxy : FileProvider() {

    companion object {
        private const val AUTHORITY_NAME = "net.typeblog.shelter.files"

        // All content Uris pointing to this path indicate a forwarded Fd.
        private const val FORWARD_PATH_PREFIX = "/forward/"

        @Volatile
        private var sProxy: UriForwardProxy? = null

        /** Register the Uri to be forwarded; returns a generated temporary Uri. */
        fun setUriForwardProxy(proxy: UriForwardProxy, suffix: String): Uri {
            sProxy = proxy
            return Uri.parse("content://$AUTHORITY_NAME$FORWARD_PATH_PREFIX" + "temp.$suffix")
        }

        /** Close and delete the current forwarder. */
        fun clearForwardProxy() {
            sProxy = null
        }
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        return if (uri.path?.startsWith(FORWARD_PATH_PREFIX) == true && sProxy != null) {
            // We are in the FORWARD_PATH_PREFIX: forward the request to the
            // UriForwardProxy, which resolves it through the binder proxy on
            // the originating profile.
            sProxy!!.open(mode)
        } else {
            super.openFile(uri, mode)
        }
    }
}