package com.inventory.share

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One image handed to the app through the Android share target: already
 * transcoded to JPEG, with the original display name and — when the
 * original carried one — its EXIF ImageDescription.
 */
class SharedImage(
    val bytes: ByteArray,
    val displayName: String,
    val exifDescription: String?,
)

/**
 * Process-wide hand-off of an inbound share between
 * [com.inventory.MainActivity] (which reads and transcodes the payloads)
 * and the root browse screen (which shows the "Add to…" container picker).
 *
 * A plain singleton state holder rather than a navigation argument, because
 * byte arrays must not travel through the nav graph and the picker only
 * ever exists while the root destination is composed. If the process is
 * killed between the intent and the picker the pending list is lost and the
 * share simply does not complete (acceptable v1: the app reopens normally).
 */
object ShareReceiver {

    private val _pending = MutableStateFlow<List<SharedImage>?>(null)

    /**
     * Images awaiting a destination; null when no share is in flight, an
     * empty list when a share arrived but carried no readable image.
     */
    val pending: StateFlow<List<SharedImage>?> = _pending.asStateFlow()

    /**
     * Confirmation of a completed share import: the container label plus
     * the landed/skipped counts, so the destination screen can either say
     * "Imported to <label>" or, when duplicates were skipped, the
     * "n imported, m skipped as duplicates" summary.
     */
    data class ImportedNotice(
        val containerLabel: String,
        val imported: Int,
        val skipped: Int,
    )

    private val _importedNotice = MutableStateFlow<ImportedNotice?>(null)

    /**
     * Confirmation of an import, observed by the destination browse
     * screen. Set just before navigating there so the snackbar appears on
     * the container the photos landed in (or were all skipped from).
     */
    val importedNotice: StateFlow<ImportedNotice?> = _importedNotice.asStateFlow()

    /** Parks the images read from an inbound share (an empty list is meaningful). */
    fun offer(images: List<SharedImage>) {
        _pending.value = images
    }

    /** Drops the pending share (picker dismissed or handled). */
    fun clear() {
        _pending.value = null
    }

    /** Arms the import confirmation for the next browse screen. */
    fun notifyImported(containerLabel: String, imported: Int, skipped: Int) {
        _importedNotice.value = ImportedNotice(containerLabel, imported, skipped)
    }

    /** Consumes the confirmation once it has been shown. */
    fun consumeImportedNotice() {
        _importedNotice.value = null
    }
}
