package com.inventory.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.inventory.storage.PhotoMetadata.PhotoMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The filesystem is the single source of truth of this app: a container is
 * a plain folder in the inventory root, a photo is a plain JPEG inside
 * such a folder, its metadata lives in the file's EXIF segment
 * ([PhotoMetadata]) and its name is derived from its description. SQLite
 * (the `cache` package) is a rebuildable index over exactly this tree —
 * everything here only touches disk.
 *
 * This class is the public facade over the two interchangeable backends
 * that speak that on-disk layout:
 *
 * - [StorageMode.SAF] (the default; zero permissions): a user-picked
 *   folder tree via [SafBackend].
 * - [StorageMode.FILE] (advanced): `/sdcard/PhotoInventory` directly via
 *   [FileBackend], requiring All Files Access.
 *
 * Identity everywhere above this layer is a RELATIVE path (root = `""`,
 * `"Building/Storage unit"`), so the same tree is seen — and edited —
 * identically no matter which mode is active; the mode is a persisted
 * user preference ([StorageModePrefs]) read per call.
 *
 * Images are stored byte-identical: captures and gallery copies are never
 * resized or recompressed; only the EXIF segment is rewritten (in memory,
 * via [PhotoMetadata.withExif]) and the result reaches disk through the
 * backend's atomic temp + rename. Dot-prefixed entries are the app's
 * private scratch (pending writes) or other tools' leftovers and are
 * excluded from every listing.
 *
 * All disk work happens on [Dispatchers.IO] inside the suspend bodies.
 */
@Singleton
class InventoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: StorageModePrefs,
    private val fileBackend: FileBackend,
    private val safBackend: SafBackend,
) {

    /** The backend the user chose, or null on first launch (chooser shows). */
    val activeMode: StorageMode?
        get() = prefs.mode()

    /** Whether the active backend may currently read and write the tree. */
    fun hasAccess(): Boolean = when (prefs.mode()) {
        StorageMode.FILE -> fileBackend.hasAccess()
        StorageMode.SAF -> safBackend.hasTree()
        null -> false
    }

    /** Creates/verifies the inventory root for the active mode. */
    suspend fun ensureRoot(): Boolean = when (prefs.mode()) {
        StorageMode.FILE -> fileBackend.ensureRoot()
        StorageMode.SAF -> safBackend.exists("")
        null -> false
    }

    private fun backend(): StorageBackend = when (prefs.mode()) {
        StorageMode.FILE -> fileBackend
        StorageMode.SAF -> safBackend
        null -> throw IllegalStateException("No storage mode chosen yet")
    }

    private fun backendOrNull(): StorageBackend? = when (prefs.mode()) {
        StorageMode.FILE -> fileBackend
        StorageMode.SAF -> safBackend
        null -> null
    }

    // region mode plumbing

    /** Points the facade at [mode] (callers pair this with a cache wipe + rescan). */
    fun setMode(mode: StorageMode) {
        prefs.setMode(mode)
        safBackend.invalidate()
    }

    /**
     * Persists [uri] as the SAF tree (taking the persistable read+write
     * grant) and activates [StorageMode.SAF]; false when the grant could
     * not be taken (the chooser then simply stays up).
     */
    fun setTreeUri(uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(uri, SAF_GRANT_FLAGS)
            prefs.setTreeUri(uri.toString())
            prefs.setMode(StorageMode.SAF)
            safBackend.invalidate()
            true
        } catch (exception: SecurityException) {
            false
        } catch (exception: IllegalArgumentException) {
            false
        }
    }

    /**
     * The `ACTION_OPEN_DOCUMENT_TREE` intent for the mode chooser: with
     * EXTRA_INITIAL_URI pointing at `/sdcard/PhotoInventory` so the picker
     * opens right at the folder the File mode would use (wrapped — devices
     * without that root just fall back to the normal picker view).
     */
    fun safPickIntent(context: Context): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        addFlags(
            SAF_GRANT_FLAGS or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
        )
        runCatching {
            val hint = DocumentsContract.buildDocumentUri(
                EXTERNAL_STORAGE_AUTHORITY,
                "primary:$ROOT_FOLDER_NAME",
            )
            if (DocumentsContract.isDocumentUri(context, hint)) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, hint)
            }
        }
    }

    /**
     * The settings intent that grants All Files Access (or null below API
     * 30, where the classic runtime pair is requested instead).
     */
    fun fileModeIntent(context: Context): Intent? = StorageAccess.storageIntent(context)

    // endregion

    // region listing & reading

    /** Child containers of the folder at [relPath]. */
    suspend fun listContainers(relPath: String): List<BackendEntry> =
        backendOrNull()?.listContainers(relPath) ?: emptyList()

    /** Photo files of the folder at [relPath] (cover and scratch excluded). */
    suspend fun listPhotos(relPath: String): List<BackendEntry> =
        backendOrNull()?.listPhotos(relPath) ?: emptyList()

    /** True when the entry at [relPath] currently exists and is reachable. */
    suspend fun exists(relPath: String): Boolean = backendOrNull()?.exists(relPath) ?: false

    /** A photo's EXIF triple via the cheap stream read; null when gone. */
    suspend fun readPhotoMeta(relPath: String): PhotoMeta? =
        backendOrNull()?.readPhotoMeta(relPath)

    /** Full bytes of an entry; null when gone. */
    suspend fun readPhotoBytes(relPath: String): ByteArray? =
        backendOrNull()?.readPhotoBytes(relPath)

    /** (lastModified, size) of an entry, or null when it is gone. */
    suspend fun lastModifiedAndSize(relPath: String): Pair<Long, Long>? =
        backendOrNull()?.lastModifiedAndSize(relPath)

    /** The stable document locator (SAF mode only); null for the File mode. */
    suspend fun documentUriFor(relPath: String): String? =
        backendOrNull()?.documentUriFor(relPath)

    /** Coil model for the folder's `_cover.jpg` (a [java.io.File] or content [Uri]), or null. */
    suspend fun coverModel(folderRel: String): Any? = backendOrNull()?.coverModel(folderRel)

    /** Coil model for the photo at [photoRel], or null when it is gone. */
    suspend fun photoModel(photoRel: String): Any? = backendOrNull()?.photoModel(photoRel)

    /** True when the folder at [relPath] has a `_cover.jpg`. */
    suspend fun hasCover(relPath: String): Boolean = backendOrNull()?.hasCover(relPath) ?: false

    /** Reads the bytes at content-[uri] verbatim (gallery picks — the originals stay put). */
    suspend fun readBytesFromUri(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    // endregion

    // region containers

    /**
     * Creates a sanitized new folder inside [parentRel]; a collision with
     * an existing entry silently yields the " (2)" variant. Returns the
     * ACTUAL relative path of the created folder.
     */
    suspend fun createFolder(parentRel: String, name: String): String {
        val base = Filenames.sanitize(name)
        return backend().createFolder(parentRel, base)
    }

    /**
     * Renames the folder at [rel] to a sanitized [newName] collision-
     * safely; returns the ACTUAL resulting relative path.
     */
    suspend fun renameFolder(rel: String, newName: String): String {
        val base = Filenames.sanitize(newName)
        return backend().renameFolder(rel, base)
    }

    /**
     * Moves the folder at [rel] under [newParentRel]. Refuses a vanished
     * parent, a move into itself or its own subtree, and reports false for
     * anything the active backend cannot do.
     */
    suspend fun moveFolder(rel: String, newParentRel: String): Boolean {
        val folderRel = rel.trimEnd('/')
        val parentRel = newParentRel.trimEnd('/')
        if (parentRel == folderRel || parentRel.startsWith("$folderRel/")) return false
        if (parentOf(folderRel) == parentRel) return true
        return backend().moveFolder(folderRel, parentRel)
    }

    /** Deletes the folder at [rel] with everything inside it. */
    suspend fun deleteFolder(rel: String) {
        backend().deleteFolderRecursive(rel)
    }

    /**
     * Sizes of [rel]'s subtree for the delete confirmation:
     * (child containers excluding the folder itself, photos in it and in
     * every descendant; covers are not counted as photos).
     */
    suspend fun subtreeCounts(rel: String): Pair<Int, Int> {
        var containers = 0
        var photos = 0
        val stack = ArrayDeque<String>()
        stack.addLast(rel)
        val active = backendOrNull() ?: return 0 to 0
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = active.listContainers(dir)
            containers += children.size
            photos += active.listPhotos(dir).size
            stack.addAll(children.map(BackendEntry::relPath))
        }
        return containers to photos
    }

    /** [rel] itself plus every descendant folder path (cycle-proof move picker). */
    suspend fun descendantFolderPaths(rel: String): List<String> {
        val paths = mutableListOf<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(rel)
        val active = backendOrNull() ?: return paths
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            paths += dir
            stack.addAll(active.listContainers(dir).map(BackendEntry::relPath))
        }
        return paths
    }

    // endregion

    // region photos

    /**
     * Adds [bytes] to the folder at [folderRel] as a permanent photo:
     * final name = sanitized [description] + ".jpg" (collision-suffixed),
     * stamped in memory with description/note (and [takenAt] as
     * DateTimeOriginal only when the payload lacks one — so camera picks
     * and gallery originals keep their real capture time), then written
     * atomically. A non-JPEG or unrewritable payload yields
     * [AddOutcome.InvalidImage] and leaves no scratch behind.
     */
    suspend fun addPhoto(
        folderRel: String,
        bytes: ByteArray,
        description: String,
        note: String?,
        takenAt: Long?,
    ): AddOutcome = withContext(Dispatchers.IO) {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return@withContext AddOutcome.BlankDescription
        if (bytes.isEmpty() || !PhotoMetadata.looksLikeJpeg(bytes)) {
            return@withContext AddOutcome.InvalidImage
        }
        val active = runCatching { backend() }.getOrNull() ?: return@withContext AddOutcome.InvalidImage
        val stamped = try {
            PhotoMetadata.withExif(
                bytes,
                description = trimmed,
                note = note?.trim()?.ifEmpty { null },
                setDateTakenIfMissing = takenAt,
            )
        } catch (exception: IOException) {
            return@withContext AddOutcome.InvalidImage
        } catch (exception: RuntimeException) {
            // ExifInterface rejects payloads it cannot parse as images.
            return@withContext AddOutcome.InvalidImage
        }
        val name = uniquePhotoName(active, folderRel, Filenames.sanitize(trimmed))
        try {
            active.writePhotoBytes(joinRel(folderRel, name), stamped)
            AddOutcome.Success
        } catch (exception: IOException) {
            AddOutcome.InvalidImage
        }
    }

    /**
     * Rewrites the photo at [rel]'s EXIF description/note and, when the
     * sanitized description differs from the current base name, moves the
     * restamped bytes to a collision-safely named entry (deleting the old
     * one). The taken date is never touched here. The caller re-looks the
     * photo up from the cache after the (possibly renamed) result.
     */
    suspend fun updatePhotoMetadata(
        rel: String,
        newDescription: String,
        newNote: String?,
    ): UpdateOutcome = withContext(Dispatchers.IO) {
        val trimmed = newDescription.trim()
        if (trimmed.isEmpty()) return@withContext UpdateOutcome.BlankDescription
        val active = runCatching { backend() }.getOrNull() ?: return@withContext UpdateOutcome.InvalidImage
        val bytes = active.readPhotoBytes(rel) ?: return@withContext UpdateOutcome.InvalidImage
        val stamped = try {
            PhotoMetadata.withExif(
                bytes,
                description = trimmed,
                note = newNote?.trim()?.ifEmpty { null },
                setDateTakenIfMissing = null,
            )
        } catch (exception: IOException) {
            return@withContext UpdateOutcome.InvalidImage
        } catch (exception: RuntimeException) {
            return@withContext UpdateOutcome.InvalidImage
        }
        val desired = Filenames.sanitize(trimmed)
        val currentName = nameOf(rel)
        val currentBase = currentName.substringBeforeLast('.', currentName)
        val targetRel = if (desired == currentBase) {
            rel
        } else {
            joinRel(parentOf(rel), uniquePhotoName(active, parentOf(rel), desired, ignoring = currentName))
        }
        try {
            active.writePhotoBytes(targetRel, stamped)
            if (targetRel != rel && !active.deleteFile(rel)) UpdateOutcome.InvalidImage
            else UpdateOutcome.Success
        } catch (exception: IOException) {
            UpdateOutcome.InvalidImage
        }
    }

    /** Deletes the photo at [rel]; a vanished photo is a no-op. */
    suspend fun deletePhoto(rel: String): Boolean = backend().deleteFile(rel)

    /**
     * Moves every photo in [rels] into [targetFolderRel], collision-
     * suffixing each name (bytes are re-stamped only by relocation — the
     * payload stays byte-identical); returns how many ended up there.
     */
    suspend fun movePhotos(rels: List<String>, targetFolderRel: String): Int {
        val active = backend()
        var moved = 0
        for (rel in rels) {
            if (parentOf(rel) == targetFolderRel) {
                moved++
                continue
            }
            val bytes = active.readPhotoBytes(rel) ?: continue
            val name = nameOf(rel)
            val ext = name.substringAfterLast('.', "jpg").ifEmpty { "jpg" }
            val base = name.substringBeforeLast('.', name)
            val targetName = uniqueNameInFolder(active, targetFolderRel, base, ext)
            try {
                active.writePhotoBytes(joinRel(targetFolderRel, targetName), bytes)
                if (active.deleteFile(rel)) moved++
            } catch (exception: IOException) {
                continue
            }
        }
        return moved
    }

    /**
     * Promotes [bytes] to [folderRel]'s `_cover.jpg` (atomic replace).
     * Throws [IllegalArgumentException] for a non-JPEG/empty payload,
     * [IOException] when the write fails.
     */
    suspend fun setCover(folderRel: String, bytes: ByteArray): Unit = backend().setCover(folderRel, bytes)

    /** Deletes the cover file of the folder at [folderRel]; missing is a no-op. */
    suspend fun removeCover(folderRel: String): Unit = backend().removeCover(folderRel)

    // endregion

    /** The first free "<base>.jpg" name inside [folderRel] (case-insensitive). */
    private suspend fun uniquePhotoName(
        active: StorageBackend,
        folderRel: String,
        base: String,
        ignoring: String? = null,
    ): String = uniqueNameInFolder(active, folderRel, base, ext = "jpg", ignoring = ignoring)

    private suspend fun uniqueNameInFolder(
        active: StorageBackend,
        folderRel: String,
        base: String,
        ext: String,
        ignoring: String? = null,
    ): String {
        val taken = active.listContainers(folderRel).map { it.name } +
            active.listPhotos(folderRel).map { it.name } +
            listOfNotNull(COVER_FILE_NAME.takeIf { active.hasCover(folderRel) })
        return Filenames.uniqueNameIn(taken.toSet(), base, ext, ignoring)
    }

    /** Result of a photo add attempt. */
    sealed interface AddOutcome {
        data object Success : AddOutcome
        data object BlankDescription : AddOutcome
        data object InvalidImage : AddOutcome
    }

    /** Result of a photo metadata update attempt. */
    sealed interface UpdateOutcome {
        data object Success : UpdateOutcome
        data object BlankDescription : UpdateOutcome
        data object InvalidImage : UpdateOutcome
    }

    private companion object {
        /** The provider serving `/sdcard/...` trees, used as the picker hint. */
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
