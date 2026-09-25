package com.inventory.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.inventory.R
import java.io.File

/**
 * Outbound sharing of the generated inventory report — the reverse
 * direction of [ShareReceiver]'s inbound SEND handling.
 *
 * The shared report generator writes `inventory-report.html` into the
 * inventory ROOT; another app can only be handed a file through a
 * [FileProvider]-exposed location, so a copy is staged under
 * `cacheDir/reports/` (the sole tree `res/xml/file_paths.xml` exposes).
 * Every staging call clears that directory first, so the cache copy never
 * grows without bound. The staged copy is handed over with `ACTION_SEND`,
 * MIME `text/html`, a `FLAG_GRANT_READ_URI_PERMISSION` grant and a
 * [ClipData] URI so OEM resolvers keep the grant when they forward it.
 */
object ReportSharing {

    /** Cache subdirectory (under `cacheDir`) exposed by the provider. */
    private const val CACHE_DIR = "reports"

    /** The manifest authority of the report [FileProvider]. */
    fun authority(context: Context): String = "${context.packageName}.fileprovider"

    /**
     * Clears `cacheDir/reports` and writes [html] as
     * `photo-inventory-<timestamp>.html`. Returns the staged file, or null
     * when the directory or the copy could not be written (the report in
     * the inventory root is unaffected either way).
     */
    fun stage(context: Context, html: ByteArray): File? {
        val dir = File(context.cacheDir, CACHE_DIR)
        dir.listFiles()?.forEach { it.delete() }
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val file = File(dir, "photo-inventory-${System.currentTimeMillis()}.html")
        return runCatching {
            file.writeBytes(html)
            file
        }.getOrNull()
    }

    /**
     * The `ACTION_SEND` intent for the staged [file]: `text/html`, the
     * stream extra under a read grant, a matching ClipData entry and the
     * report subject. The two grant mechanisms together keep the read
     * permission alive across every resolver on the device.
     */
    fun sendIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/html"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_share_subject))
            clipData = ClipData.newUri(context.contentResolver, "report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** The system chooser for [file], titled `share_report_chooser`. */
    fun chooser(context: Context, file: File): Intent =
        Intent.createChooser(sendIntent(context, file), context.getString(R.string.share_report_chooser))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}
