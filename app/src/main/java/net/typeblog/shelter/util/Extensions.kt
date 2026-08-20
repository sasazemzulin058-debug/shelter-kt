package net.typeblog.shelter.util

import android.os.RemoteException

// Canonicalize a document id against DUMMY_ROOT and reject any path that
// escapes the root or attempts parent traversal. Reassembles the normalized
// path (dropping empty and "." segments) so the same document id is always
// mapped to a single canonical form. Throws RemoteException on invalid input
// so provider callers can map it to a "not found" / false result.
internal fun String.sanitizeDocumentPath(): String {
    if (!startsWith(CrossProfileDocumentsProvider.DUMMY_ROOT)) {
        throw RemoteException("Invalid document path")
    }
    val cleaned = ArrayList<String>(16)
    for (part in split('/')) {
        when (part) {
            "", "." -> Unit
            ".." -> throw RemoteException("Invalid document path: traversal")
            else -> cleaned.add(part)
        }
    }
    return if (cleaned.isEmpty()) {
        CrossProfileDocumentsProvider.DUMMY_ROOT
    } else {
        CrossProfileDocumentsProvider.DUMMY_ROOT + cleaned.joinToString("/")
    }
}
