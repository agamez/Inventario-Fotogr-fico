package com.inventory.storage

/**
 * A storage backend: the same on-disk inventory model (folders are
 * containers, JPEGs are photos carrying their EXIF triple, `_cover.jpg`
 * is a folder's cover) reached through two different doors.
 *
 * - [FileBackend] talks to `/sdcard/PhotoInventory` directly with the
 *   `File` API and needs All Files Access.
 * - [SafBackend] talks to a user-picked tree through the Storage Access
 *   Framework and needs no runtime permission at all.
 *
 * Every path here is RELATIVE to the storage root (root = `""`,
 * `"Building"`, `"Building/Storage unit"`), so cache rows, navigation
 * routes and both backends share one identity for one entry; the physical
 * addressing (a `File` under a fixed folder, or a document tree under an
 * arbitrary SAF root) is each backend's private concern.
 *
 * All implementations are suspend and do their I/O on
 * [kotlinx.coroutines.Dispatchers.IO] internally.
 */
interface StorageBackend {

    /** Child containers (non-hidden directories) of [relPath]. */
    suspend fun listContainers(relPath: String): List<BackendEntry>

    /**
     * Photo files of [relPath]: `*.jpg`/`*.jpeg` (case-insensitive),
     * excluding the container cover [_COVER_FILE_NAME] and dot-prefixed
     * entries (the app's atomic-write scratch and other tools' droppings).
     */
    suspend fun listPhotos(relPath: String): List<BackendEntry>

    /** True when the entry at [relPath] currently exists and is reachable. */
    suspend fun exists(relPath: String): Boolean

    /**
     * A photo's EXIF triple through a cheap stream read (pixel data is
     * never decoded); null when the entry is gone or unreadable.
     */
    suspend fun readPhotoMeta(relPath: String): PhotoMetadata.PhotoMeta?

    /** Full bytes of an entry (search thumbnails, EXIF rewrites); null when gone. */
    suspend fun readPhotoBytes(relPath: String): ByteArray?

    /**
     * Creates or replaces the entry at [relPath] with [bytes], atomically
     * (temp + rename semantics); throws [java.io.IOException] on failure.
     */
    suspend fun writePhotoBytes(relPath: String, bytes: ByteArray)

    /**
     * Creates a folder called [name] (a single, already-sanitized segment)
     * inside [parentRel]; a collision with an existing entry yields the
     * " (2)" variant. Returns the ACTUAL created relPath.
     */
    suspend fun createFolder(parentRel: String, name: String): String

    /**
     * Renames the folder at [rel] to [newName] collision-safely; returns
     * the ACTUAL resulting relPath (equal to [rel] when nothing changed).
     */
    suspend fun renameFolder(rel: String, newName: String): String

    /**
     * Moves the folder at [rel] directly inside [newParentRel] ("" = the
     * storage root). False when unsupported or failed — disk untouched.
     */
    suspend fun moveFolder(rel: String, newParentRel: String): Boolean

    /** Deletes the folder at [rel] together with its whole subtree. */
    suspend fun deleteFolderRecursive(rel: String)

    /** Deletes a single file; a vanished file is a no-op. Returns whether the path is gone. */
    suspend fun deleteFile(rel: String): Boolean

    /** Coil model (a [java.io.File] or a content [android.net.Uri]) for the folder's `_cover.jpg`, or null. */
    suspend fun coverModel(rel: String): Any?

    /** Coil model for the photo file at [rel], or null when it is gone. */
    suspend fun photoModel(rel: String): Any?

    /** Promotes [bytes] to the folder's `_cover.jpg`, replacing any previous cover. */
    suspend fun setCover(folderRel: String, bytes: ByteArray)

    /** Deletes the folder's cover file; a missing cover is a no-op. */
    suspend fun removeCover(folderRel: String)

    /** True when the folder at [rel] has a `_cover.jpg`. */
    suspend fun hasCover(rel: String): Boolean

    /** (lastModified, size) of an entry, or null when it is gone. */
    suspend fun lastModifiedAndSize(rel: String): Pair<Long, Long>?

    /** The backend's stable document locator for [rel] (SAF only); null elsewhere. */
    suspend fun documentUriFor(rel: String): String?
}

/**
 * One child row of a [StorageBackend] listing: its relative path (the
 * universal identity), display name, sync-conflict marker and stat data.
 */
data class BackendEntry(
    val relPath: String,
    val name: String,
    val isConflict: Boolean,
    val lastModified: Long,
    val size: Long,
)

/** Folder name under the external-storage root in File mode, e.g. `/sdcard/PhotoInventory`. */
const val ROOT_FOLDER_NAME = "PhotoInventory"

/** A container's cover lives in this fixed file inside its folder. */
const val COVER_FILE_NAME = "_cover.jpg"

/** Syncthing-style conflict marker: `name.sync-conflict-YYYYMMDD-HHMMSS-DEV`. */
private val CONFLICT_REGEX = Regex("""\.sync-conflict-\d{8}-\d{6}-\w+""")

/** True when a file/folder name carries a Syncthing-style conflict marker. */
internal fun isConflictName(name: String): Boolean = CONFLICT_REGEX.containsMatchIn(name)

/** Appends a single [name] segment to a relative parent path ("" = root). */
internal fun joinRel(parent: String, name: String): String =
    if (parent.isEmpty()) name else "$parent/$name"

/** The relative parent of [rel] ("" for direct children of the root). */
internal fun parentOf(rel: String): String = rel.substringBeforeLast('/', "")

/** The last (name) segment of a relative path. */
internal fun nameOf(rel: String): String = rel.substringAfterLast('/')
