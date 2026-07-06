package net.rpcsx.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Maps a Storage Access Framework tree/document URI to a real filesystem path when possible.
 *
 * The native RPCSX core opens games through std::filesystem (real paths), not SAF, so a
 * user-picked folder or file can only be scanned / played in place if its content:// URI maps
 * back to a real /storage path. The externalstorage documents provider (which the system file
 * picker and frontends like Daijisho / ES-DE produce for on-device and SD-card storage) exposes
 * that mapping; other providers (cloud, MTP) do not.
 */
object SafPath {
    /**
     * Persist read access to [uri] and return the real filesystem path it maps to, or null if it
     * cannot be mapped (a non-externalstorage provider, or the mapped path does not exist). A null
     * result means the core cannot scan/open it directly - the caller should warn the user.
     */
    fun resolveTreeUriToRealPath(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        if (uri.authority != "com.android.externalstorage.documents") {
            return null
        }

        val docId = runCatching { DocumentsContract.getDocumentId(uri) }
            .recoverCatching { DocumentsContract.getTreeDocumentId(uri) }
            .getOrNull() ?: return null

        val parts = docId.split(":", limit = 2)
        if (parts.size != 2) {
            return null
        }

        val (volume, relPath) = parts
        val base = if (volume == "primary") {
            Environment.getExternalStorageDirectory().absolutePath
        } else {
            "/storage/$volume"
        }
        val path = if (relPath.isEmpty()) base else "$base/$relPath"

        return if (File(path).exists()) path else null
    }
}
