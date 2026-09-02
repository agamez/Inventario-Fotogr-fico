package com.inventory.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * The storage-permission model in one place.
 *
 * The app reads and writes a normal folder tree under
 * `/sdcard/PhotoInventory`, which needs broad file access:
 *
 * - API 30+: All Files Access (`MANAGE_EXTERNAL_STORAGE`), granted on the
 *   system settings page reached through [storageIntent].
 * - API 24–29: the classic `READ`/`WRITE_EXTERNAL_STORAGE` runtime pair;
 *   on API 29 the manifest's `requestLegacyExternalStorage` flag keeps the
 *   `File` API on the whole volume, so a granted WRITE is sufficient.
 */
object StorageAccess {

    /** True when the app may currently read and write the inventory tree. */
    fun hasStorageAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
        }

    /**
     * The settings intent that grants storage access, or null on API < 30
     * where access is taken through the runtime permission dialog instead
     * (the screen fires `RequestMultiplePermissions` for READ + WRITE).
     */
    fun storageIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val appScoped = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + context.packageName),
        )
        return if (appScoped.resolveActivity(context.packageManager) != null) {
            appScoped
        } else {
            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        }
    }
}
