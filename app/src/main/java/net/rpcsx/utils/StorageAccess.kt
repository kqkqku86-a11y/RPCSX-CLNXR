package net.rpcsx.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * All-files (raw-path) storage access, needed for the native core to play games directly from a
 * user-chosen folder anywhere on storage. This is the special MANAGE_EXTERNAL_STORAGE permission
 * (granted in system settings, not via the normal runtime dialog) on Android 11+, and legacy
 * READ_EXTERNAL_STORAGE on Android 10.
 */
object StorageAccess {
    /** True when the app can read arbitrary storage paths by raw filesystem path. */
    fun hasAllFilesAccess(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Build the Intent for the per-app all-files-access screen WITHOUT FLAG_ACTIVITY_NEW_TASK, so
     * it can be launched via an ActivityResultLauncher (which delivers a callback when the user
     * returns, even though the settings screen sets no result). Returns null pre-R (Android 10 uses
     * a runtime READ_EXTERNAL_STORAGE grant instead). We deliberately do NOT gate on resolveActivity
     * here: under Android 11 package-visibility filtering it can return null for a Settings action
     * even though startActivity succeeds, which would needlessly drop the seamless return path. The
     * caller launches inside runCatching and falls back to [requestAllFilesAccess] on failure.
     */
    fun buildAllFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return null
        }

        return Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            .setData(Uri.fromParts("package", context.packageName, null))
    }

    /**
     * Send the user to the screen where they grant all-files access. On Android 11+ this is the
     * per-app "Allow access to manage all files" toggle; falls back to the generic list screen if
     * the app-specific one is unavailable. Returns false if no screen could be opened.
     */
    fun requestAllFilesAccess(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false // Android 10: covered by legacy storage + READ_EXTERNAL_STORAGE
        }

        val flags = Intent.FLAG_ACTIVITY_NEW_TASK

        return runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.fromParts("package", context.packageName, null))
                    .addFlags(flags)
            )
            true
        }.recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(flags)
            )
            true
        }.getOrDefault(false)
    }
}
