package com.inventory.cache

import androidx.room.Entity

/**
 * One row of the disposable storage index: a mirror of a filesystem entry
 * (folder or photo) under `/sdcard/PhotoInventory`, keyed by its absolute
 * path. The row holds no image bytes — the UI loads the file at [path]
 * directly; photos additionally carry the EXIF triple ([description],
 * [note], [dateTaken]) parsed by the scanner.
 *
 * [parentPath] is the absolute path of the containing folder, or null for
 * entries that live directly in the inventory root (SQL NULL, so queries
 * compare it with `IS`).
 */
@Entity(tableName = "index_entries", primaryKeys = ["path"])
data class CacheEntry(
    val path: String,
    val parentPath: String?,
    val name: String,
    val kind: Int,
    val isConflict: Boolean = false,
    val description: String? = null,
    val note: String? = null,
    val dateTaken: Long? = null,
    val lastModified: Long = 0L,
    val size: Long = 0L,
    val hasCover: Boolean = false,
) {

    companion object {
        /** kind value: a container folder. */
        const val KIND_CONTAINER = 0

        /** kind value: a photo JPEG file. */
        const val KIND_PHOTO = 1
    }
}
