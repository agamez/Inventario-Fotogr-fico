package com.inventory.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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

    // region items

    /**
     * The parsed `_item.json` marker of [folderRel], or null when the
     * folder carries no marker, its payload is corrupt or it is a foreign
     * marker (unknown `type`) — all of which mean "ordinary container".
     * Never throws on bad content: a corrupt marker can only demote the
     * folder, never block a scan.
     */
    suspend fun readItemMarker(folderRel: String): ItemMarkerData? {
        val bytes = backendOrNull()?.readAuxFile(joinRel(folderRel, ITEM_MARKER)) ?: return null
        return ItemMarker.parse(bytes)?.takeIf { ItemMarker.isItem(it) }
    }

    /** True when [folderRel] is an item (a folder carrying a valid marker). */
    suspend fun isItem(folderRel: String): Boolean = readItemMarker(folderRel) != null

    /**
     * Creates an EMPTY item under [parentRel]: a collision-suffixed new
     * folder carrying [_item.json] with the optional [note]. An unmarked
     * folder would silently read as a container, so a failed marker write
     * rolls the new folder back and reports null (disk left untouched).
     *
     * Returns the ACTUAL relPath of the created item.
     */
    suspend fun createItem(parentRel: String, name: String, note: String? = null): String? =
        withContext(Dispatchers.IO) {
            val active = backendOrNull() ?: return@withContext null
            val parent = parentRel.trimEnd('/')
            // Rule 4: items never nest — refusing here protects every caller.
            if (parent.isNotEmpty() && readItemMarker(parent) != null) return@withContext null
            val rel = try {
                active.createFolder(parent, Filenames.sanitize(name))
            } catch (exception: IOException) {
                return@withContext null
            }
            val marker = ItemMarker.writeItemMarker(note?.trim()?.ifEmpty { null })
            try {
                active.writePhotoBytes(joinRel(rel, ITEM_MARKER), marker)
                rel
            } catch (exception: IOException) {
                active.deleteFolderRecursive(rel)
                null
            }
        }

    /**
     * Rewrites the element note in `_item.json` (atomic replace through the
     * backend's aux write). Blank [note] drops the field. Returns false when
     * [itemRel] is not an item or the write fails.
     */
    suspend fun updateItemNote(itemRel: String, note: String?): Boolean =
        withContext(Dispatchers.IO) {
            val active = backendOrNull() ?: return@withContext false
            val rel = itemRel.trimEnd('/')
            if (rel.isEmpty() || readItemMarker(rel) == null) return@withContext false
            val marker = ItemMarker.writeItemMarker(note?.trim()?.ifEmpty { null })
            try {
                active.writePhotoBytes(joinRel(rel, ITEM_MARKER), marker)
                true
            } catch (exception: IOException) {
                false
            }
        }

    /** Renames the item folder at [itemRel]; returns the ACTUAL resulting relPath. */
    suspend fun renameItem(itemRel: String, newName: String): String {
        if (!isItem(itemRel)) return itemRel
        return renameFolder(itemRel, newName)
    }

    /**
     * Moves the item folder at [itemRel] under [newParentRel]. Items never
     * accept containers and never nest (rule 4), so a destination that is
     * itself an item refuses the move without touching disk.
     */
    suspend fun moveItem(itemRel: String, newParentRel: String): Boolean {
        val parentRel = newParentRel.trimEnd('/')
        if (parentRel.isNotEmpty() && isItem(parentRel)) return false
        return moveFolder(itemRel, parentRel)
    }

    /**
     * "Flatten to a single photo" (rule 3): the item's ONLY photo moves out
     * into the parent container under the item's name (collision-suffixed)
     * and the item folder — marker included — is deleted. Returns false and
     * touches nothing unless exactly one photo remains inside: degrading
     * 2+ photos to one is never automatic, only this explicit manual
     * action (a 1-photo item simply renders as a normal tile).
     */
    suspend fun flattenItem(itemRel: String): Boolean = withContext(Dispatchers.IO) {
        val active = backendOrNull() ?: return@withContext false
        if (readItemMarker(itemRel) == null) return@withContext false
        val photos = active.listPhotos(itemRel)
        if (photos.size != 1) return@withContext false
        val photo = photos.single()
        val ext = photo.name.substringAfterLast('.', "jpg").ifEmpty { "jpg" }
        val parentRel = parentOf(itemRel)
        val bytes = active.readPhotoBytes(photo.relPath) ?: return@withContext false
        val targetName = uniqueNameInFolder(
            active,
            parentRel,
            Filenames.sanitize(nameOf(itemRel)),
            ext,
        )
        val targetRel = joinRel(parentRel, targetName)
        try {
            active.writePhotoBytes(targetRel, bytes)
        } catch (exception: IOException) {
            return@withContext false
        }
        if (!active.deleteFile(photo.relPath)) {
            active.deleteFile(targetRel)
            return@withContext false
        }
        active.deleteFolderRecursive(itemRel)
        true
    }

    /**
     * Removes the item marker only: the folder degrades to an ordinary
     * container with all its photos (and any ignored subfolders) in place.
     * A folder left empty (no photos, subfolders or cover — e.g. an item
     * created and never filled) is deleted entirely, since it only existed
     * as an item. Returns false when [itemRel] is not an item.
     */
    suspend fun ungroup(itemRel: String): Boolean = withContext(Dispatchers.IO) {
        val active = backendOrNull() ?: return@withContext false
        if (readItemMarker(itemRel) == null) return@withContext false
        if (!active.deleteFile(joinRel(itemRel, ITEM_MARKER))) return@withContext false
        val emptied = active.listPhotos(itemRel).isEmpty() &&
            active.listContainers(itemRel).isEmpty() &&
            !active.hasCover(itemRel)
        if (emptied) active.deleteFolderRecursive(itemRel)
        true
    }

    /**
     * Groups [photoRels] into a NEW item folder called [name] inside
     * [parentRel]: folder + marker first, then the photos move in
     * byte-identically (temp + rename, never re-encoded) with
     * collision-suffixed " (2)" names. When no photo could be moved the
     * just-created item is rolled back and null is returned.
     */
    suspend fun groupPhotosIntoItem(
        parentRel: String,
        name: String,
        photoRels: List<String>,
        note: String? = null,
    ): String? = withContext(Dispatchers.IO) {
        val active = backendOrNull() ?: return@withContext null
        val sources = photoRels.map { it.trimEnd('/') }.distinct()
        if (sources.isEmpty()) return@withContext null
        val itemRel = createItem(parentRel, name, note) ?: return@withContext null
        if (movePhotos(sources, itemRel) == 0) {
            active.deleteFolderRecursive(itemRel)
            null
        } else {
            itemRel
        }
    }

    /**
     * Adds existing photos to the element at [elementRel]:
     *
     * - an item folder receives the photos directly ([movePhotos] keeps
     *   the payload byte-identical);
     * - a single-photo element — a loose JPEG file — is PROMOTED first
     *   (rule 2): a `<stem>/` folder carrying the marker is created next
     *   to it, the existing file moves inside byte-identically and the new
     *   photos follow it. A promotion whose existing photo cannot move is
     *   rolled back, so no half-element is left behind.
     *
     * Returns the resulting item relPath and how many photos ended up in
     * it, or a failure case when nothing moved.
     */
    suspend fun addPhotoToItem(elementRel: String, photoRels: List<String>): ItemAddOutcome =
        withContext(Dispatchers.IO) {
            val active = backendOrNull() ?: return@withContext ItemAddOutcome.NotFound
            val target = elementRel.trimEnd('/')
            if (target.isEmpty()) return@withContext ItemAddOutcome.NotFound
            val additions = photoRels.map { it.trimEnd('/') }.distinct().filter { it != target }
            if (additions.isEmpty()) return@withContext ItemAddOutcome.NoPhotosMoved
            if (readItemMarker(target) != null) {
                val moved = movePhotos(additions, target)
                return@withContext if (moved > 0) {
                    ItemAddOutcome.Success(target, moved)
                } else {
                    ItemAddOutcome.NoPhotosMoved
                }
            }
            if (!isSinglePhotoElement(active, target)) return@withContext ItemAddOutcome.NotFound
            val parentRel = parentOf(target)
            val stem = nameOf(target).substringBeforeLast('.', nameOf(target))
            val itemRel = createItem(parentRel, stem) ?: return@withContext ItemAddOutcome.NotFound
            if (movePhotos(listOf(target), itemRel) != 1) {
                active.deleteFolderRecursive(itemRel)
                return@withContext ItemAddOutcome.NoPhotosMoved
            }
            val moved = movePhotos(additions, itemRel)
            ItemAddOutcome.Success(itemRel, 1 + moved)
        }

    /** True when [rel] is an existing loose JPEG file — the 1-photo element shape. */
    private suspend fun isSinglePhotoElement(active: StorageBackend, rel: String): Boolean {
        val name = nameOf(rel)
        if (name.startsWith(".")) return false
        val ext = name.substringAfterLast('.', "")
        if (!ext.equals("jpg", ignoreCase = true) && !ext.equals("jpeg", ignoreCase = true)) {
            return false
        }
        return active.exists(rel)
    }

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

    /** Result of adding existing photos to an element (see [addPhotoToItem]). */
    sealed interface ItemAddOutcome {
        /** [moved] photos now live in the item at [itemRel]. */
        data class Success(val itemRel: String, val moved: Int) : ItemAddOutcome

        /** [addPhotoToItem]'s element is neither an item nor a single-photo element file. */
        data object NotFound : ItemAddOutcome

        /** Nothing moved (sources vanished, or the promotion could not complete). */
        data object NoPhotosMoved : ItemAddOutcome
    }

    private companion object {
        /** The provider serving `/sdcard/...` trees, used as the picker hint. */
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
