package com.inventory.storage

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Soft-delete / undo for the filesystem inventory.
 *
 * Deletions never destroy content: the entry is MOVED into a timestamped
 * stamp folder under the dot-prefixed `<root>/.trash/` tree, so it drops
 * out of every listing and the scanner, yet stays on disk (and is carried
 * along by Syncthing) until it is restored or purged.
 *
 * Layout:
 *
 * ```
 * .trash/
 *   20260924-101530/
 *     Box A/            <- the trashed container (with its whole subtree)
 *       Keep me.jpg
 *     .origin           <- sidecar: line 1 = original parent relPath
 *                                    line 2 = "container" | "photo"
 * ```
 *
 * A batch of photos is trashed as one stamp per photo (NOT one shared
 * stamp), because each sidecar records exactly one original parent —
 * selections can span folders (subtree search), and sharing a stamp would
 * lose their homes. Colliding stamp names are suffix-resolved by
 * [StorageBackend.createFolder] (" (2)"), and the parser reads the leading
 * `yyyyMMdd-HHmmss` back out of the name.
 *
 * Restore moves the single child back to the recorded parent (collision-
 * suffixed by [Filenames]) and drops the stamp. [purgeOlderThan] deletes
 * stamp folders older than the cutoff and [purgeOverSizeCap] drops the
 * oldest entries while the tree exceeds a byte cap; the app runs both
 * opportunistically at start when the trash is enabled. All I/O runs on
 * [Dispatchers.IO].
 */
class TrashManager(
    private val backend: StorageBackend,
    private val clock: () -> Long = { java.lang.System.currentTimeMillis() },
) {

    /**
     * One restorable trashed entry: [id] is the stamp folder name under
     * `.trash/`, [name] the original entry name, [originalParentRel] the
     * folder it was deleted from (`""` = the storage root) and [kind]
     * either [KIND_CONTAINER] or [KIND_PHOTO].
     */
    data class TrashEntry(
        val id: String,
        val name: String,
        val originalParentRel: String,
        val kind: String,
        val deletedAt: Long,
    )

    /** Moves the container at [relPath] (with its subtree) into the trash. */
    suspend fun trashContainer(relPath: String): Boolean = withContext(Dispatchers.IO) {
        if (relPath.isEmpty()) return@withContext false
        val stampRel = newStampFolder()
        if (backend.moveFolder(relPath, stampRel)) {
            writeOrigin(stampRel, parentOf(relPath), KIND_CONTAINER)
            true
        } else {
            // Nothing was moved: drop the empty stamp so the trash stays clean.
            backend.deleteFolderRecursive(stampRel)
            false
        }
    }

    /**
     * Moves every photo in [relPaths] into the trash, one stamp folder per
     * photo so each sidecar keeps its own original parent. Returns true
     * only when every photo was moved.
     */
    suspend fun trashPhotos(relPaths: List<String>): Boolean = withContext(Dispatchers.IO) {
        if (relPaths.isEmpty()) return@withContext false
        var allMoved = true
        for (relPath in relPaths) {
            val stampRel = newStampFolder()
            if (movePhoto(relPath, stampRel)) {
                writeOrigin(stampRel, parentOf(relPath), KIND_PHOTO)
            } else {
                backend.deleteFolderRecursive(stampRel)
                allMoved = false
            }
        }
        allMoved
    }

    /** Every restorable entry, newest deletion first. */
    suspend fun listEntries(): List<TrashEntry> = withContext(Dispatchers.IO) {
        backend.listContainers(TRASH_DIR).mapNotNull { stamp ->
            val origin = readOrigin(stamp.relPath) ?: return@mapNotNull null
            val child = when (origin.second) {
                KIND_PHOTO -> backend.listPhotos(stamp.relPath).firstOrNull()
                else -> backend.listContainers(stamp.relPath).firstOrNull()
            } ?: return@mapNotNull null
            TrashEntry(
                id = stamp.name,
                name = child.name,
                originalParentRel = origin.first,
                kind = origin.second,
                deletedAt = parseStamp(stamp.name) ?: stamp.lastModified,
            )
        }.sortedByDescending { it.deletedAt }
    }

    /**
     * Puts the entry [id] back into its recorded parent (collision-suffix
     * " (2)" when the name is taken) and removes the now-empty stamp.
     * False when the entry or its original parent is gone.
     */
    suspend fun restore(id: String): Boolean = withContext(Dispatchers.IO) {
        val stampRel = joinRel(TRASH_DIR, id)
        val origin = readOrigin(stampRel) ?: return@withContext false
        val parentRel = origin.first
        if (parentRel.isNotEmpty() && !backend.exists(parentRel)) return@withContext false
        val restored = when (origin.second) {
            KIND_PHOTO -> restorePhoto(stampRel, parentRel)
            else -> restoreContainer(stampRel, parentRel)
        }
        if (restored) {
            backend.deleteFile(joinRel(stampRel, ORIGIN_FILE))
            backend.deleteFolderRecursive(stampRel)
        }
        restored
    }

    /** Permanently deletes the trash entry [id] (its whole stamp folder). */
    suspend fun deleteForever(id: String): Boolean = withContext(Dispatchers.IO) {
        val stampRel = joinRel(TRASH_DIR, id)
        if (!backend.exists(stampRel)) return@withContext true
        backend.deleteFolderRecursive(stampRel)
        !backend.exists(stampRel)
    }

    /**
     * Permanently deletes every entry deleted more than [days] days ago;
     * returns how many stamps were dropped. Entries whose stamp cannot be
     * dated (lastModified 0) are kept.
     */
    suspend fun purgeOlderThan(days: Int = 30): Int = withContext(Dispatchers.IO) {
        val cutoff = clock() - days * DAY_MILLIS
        if (cutoff <= 0L) return@withContext 0
        var purged = 0
        for (entry in listEntries()) {
            if (entry.deletedAt >= 1L && entry.deletedAt < cutoff && deleteForever(entry.id)) purged++
        }
        purged
    }

    /**
     * The total size in bytes of everything under `.trash` — every stamp
     * folder, its whole subtree, nested covers and the `.origin` sidecars.
     * Zero when the trash does not exist or cannot be reached.
     */
    suspend fun totalTrashBytes(): Long = withContext(Dispatchers.IO) {
        if (!backend.exists(TRASH_DIR)) return@withContext 0L
        var total = 0L
        for (stamp in backend.listContainers(TRASH_DIR)) {
            total += stampedBytes(stamp.relPath)
        }
        total
    }

    /**
     * Permanently deletes the OLDEST entries (by deletion time) until
     * [totalTrashBytes] is at or below [maxBytes]; returns how many stamps
     * were dropped. The newest content survives a disk-space squeeze, so
     * the cap complements [purgeOlderThan] without ever dropping fresh
     * deletions while old ones remain. The app runs both when the trash is
     * enabled.
     */
    suspend fun purgeOverSizeCap(maxBytes: Long = MAX_TRASH_BYTES): Int = withContext(Dispatchers.IO) {
        var total = totalTrashBytes()
        if (total <= maxBytes) return@withContext 0
        var purged = 0
        for (entry in listEntries().asReversed()) {
            if (total <= maxBytes) break
            val stampRel = joinRel(TRASH_DIR, entry.id)
            val freed = stampedBytes(stampRel)
            if (deleteForever(entry.id)) {
                total -= freed
                purged++
            }
        }
        purged
    }

    // region internals

    /** Size of one stamp folder including its `.origin` sidecar. */
    private suspend fun stampedBytes(stampRel: String): Long =
        subtreeBytes(stampRel) +
            (backend.lastModifiedAndSize(joinRel(stampRel, ORIGIN_FILE))?.second ?: 0L)

    /** Recursive byte total of a folder's photos, covers and subfolders. */
    private suspend fun subtreeBytes(rel: String): Long {
        var total = 0L
        if (backend.hasCover(rel)) {
            total += backend.lastModifiedAndSize(joinRel(rel, COVER_FILE_NAME))?.second ?: 0L
        }
        for (photo in backend.listPhotos(rel)) total += photo.size
        for (child in backend.listContainers(rel)) total += subtreeBytes(child.relPath)
        return total
    }

    /** Ensures `.trash` exists (without colliding itself into `.trash (2)`). */
    private suspend fun ensureTrashRoot() {
        if (!backend.exists(TRASH_DIR)) {
            runCatching { backend.createFolder("", TRASH_DIR) }
        }
    }

    /** A fresh, timestamped stamp folder; collisions get the " (2)" suffix. */
    private suspend fun newStampFolder(): String {
        ensureTrashRoot()
        return backend.createFolder(TRASH_DIR, stampName(clock()))
    }

    /** Copies a photo file into [stampRel] and only then removes the original. */
    private suspend fun movePhoto(relPath: String, stampRel: String): Boolean {
        val bytes = backend.readPhotoBytes(relPath) ?: return false
        val targetRel = joinRel(stampRel, nameOf(relPath))
        return try {
            backend.writePhotoBytes(targetRel, bytes)
            if (backend.deleteFile(relPath)) {
                true
            } else {
                backend.deleteFile(targetRel)
                false
            }
        } catch (exception: IOException) {
            false
        }
    }

    private suspend fun restoreContainer(stampRel: String, parentRel: String): Boolean {
        val child = backend.listContainers(stampRel).firstOrNull() ?: return false
        return backend.moveFolder(child.relPath, parentRel)
    }

    private suspend fun restorePhoto(stampRel: String, parentRel: String): Boolean {
        val child = backend.listPhotos(stampRel).firstOrNull() ?: return false
        val bytes = backend.readPhotoBytes(child.relPath) ?: return false
        val extension = child.name.substringAfterLast('.', "jpg").ifEmpty { "jpg" }
        val base = child.name.substringBeforeLast('.', child.name)
        val taken = backend.listContainers(parentRel).map { it.name } +
            backend.listPhotos(parentRel).map { it.name }
        val targetName = Filenames.uniqueNameIn(taken.toSet(), base, extension)
        return try {
            backend.writePhotoBytes(joinRel(parentRel, targetName), bytes)
            backend.deleteFile(child.relPath)
        } catch (exception: IOException) {
            false
        }
    }

    /**
     * The sidecar is plain text written through the backend's generic
     * byte-write primitive (the same one photos use); it is neither EXIF
     * parsed nor ever listed, so no JPEG gate applies.
     */
    private suspend fun writeOrigin(stampRel: String, parentRel: String, kind: String) {
        val text = parentRel + "\n" + kind + "\n"
        backend.writePhotoBytes(joinRel(stampRel, ORIGIN_FILE), text.toByteArray(Charsets.UTF_8))
    }

    private suspend fun readOrigin(stampRel: String): Pair<String, String>? {
        val bytes = backend.readPhotoBytes(joinRel(stampRel, ORIGIN_FILE)) ?: return null
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        val parent = lines.getOrNull(0)?.trim().orEmpty()
        val kind = lines.getOrNull(1)?.trim().orEmpty().ifEmpty { KIND_CONTAINER }
        return parent to kind
    }

    private fun stampName(at: Long): String =
        SimpleDateFormat(STAMP_PATTERN, Locale.US).format(Date(at))

    /** Reads the leading `yyyyMMdd-HHmmss` from a (possibly suffixed) stamp name. */
    private fun parseStamp(name: String): Long? {
        val match = STAMP_REGEX.find(name) ?: return null
        return runCatching {
            SimpleDateFormat(STAMP_PATTERN, Locale.US).parse(match.groupValues[1])?.time
        }.getOrNull()
    }

    // endregion

    companion object {
        /** Dot-prefixed trash root under the inventory root. */
        const val TRASH_DIR = ".trash"

        /** Plain-text sidecar recording the original parent and kind. */
        const val ORIGIN_FILE = ".origin"

        /** [TrashEntry.kind] value of a trashed container. */
        const val KIND_CONTAINER = "container"

        /** [TrashEntry.kind] value of a trashed photo. */
        const val KIND_PHOTO = "photo"

        /** Default cap on the whole `.trash` tree: 500 MB. */
        const val MAX_TRASH_BYTES = 500L * 1024 * 1024

        private const val STAMP_PATTERN = "yyyyMMdd-HHmmss"
        private val STAMP_REGEX = Regex("""(\d{8}-\d{6})""")
        private const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}
