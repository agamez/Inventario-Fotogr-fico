package com.inventory.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The filesystem is the single source of truth of this app: a container is
 * a plain folder under `/sdcard/PhotoInventory`, a photo is a plain JPEG
 * inside such a folder, its metadata lives in the file's EXIF segment
 * ([PhotoMetadata]) and its name is derived from its description. SQLite
 * (the `cache` package) is a rebuildable index over exactly this tree —
 * everything here only touches disk.
 *
 * Images are stored byte-identical: captures and gallery copies are never
 * resized or recompressed; only the EXIF segment is (atomically) rewritten.
 * Every mutation is crash-safe — write a temp file inside the destination
 * folder first, then rename it into place. Dot-prefixed entries are the
 * app's private scratch (pending temps) or other tools' leftovers and are
 * excluded from every listing.
 *
 * All disk work happens on [Dispatchers.IO] inside the suspend bodies.
 */
@Singleton
class InventoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** The inventory root folder; created lazily by [ensureRoot]. */
    val root: File = File(Environment.getExternalStorageDirectory(), ROOT_FOLDER_NAME)

    /** Absolute path of [root] — the prefix every cache row hangs off. */
    val rootPath: String = root.absolutePath

    /** Whether the app may currently read/write [root] (see [StorageAccess]). */
    fun hasAccess(): Boolean = StorageAccess.hasStorageAccess(context)

    /** Creates [root] if needed; false when storage is inaccessible or mkdirs failed. */
    suspend fun ensureRoot(): Boolean = withContext(Dispatchers.IO) {
        hasAccess() && (root.isDirectory || root.mkdirs())
    }

    // region listing (pure File helpers — call from a background dispatcher)

    /** Child containers of [folder]: directories without a leading dot. */
    fun listContainers(folder: File): List<File> =
        folder.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.toList()
            ?: emptyList()

    /**
     * Photo files of [folder]: `*.jpg`/`*.jpeg` (case-insensitive), minus
     * the container cover [_cover.jpg] and minus dot-prefixed entries.
     */
    fun listPhotos(folder: File): List<File> =
        folder.listFiles()
            ?.filter {
                it.isFile &&
                    !it.name.startsWith(".") &&
                    !it.name.equals(COVER_FILE_NAME, ignoreCase = true) &&
                    (it.extension.equals("jpg", ignoreCase = true) ||
                        it.extension.equals("jpeg", ignoreCase = true))
            }
            ?.toList()
            ?: emptyList()

    /** True when a file/folder name carries a Syncthing-style conflict marker. */
    fun isConflict(name: String): Boolean = CONFLICT_REGEX.containsMatchIn(name)

    /** The folder's cover file when it has one, else null. */
    fun coverFile(folder: File): File? = folder.resolve(COVER_FILE_NAME).takeIf { it.isFile }

    // endregion

    // region covers

    /**
     * Promotes the staged image to the folder's `_cover.jpg` (temp copy
     * inside the folder + atomic rename), replacing any previous cover and
     * deleting [staged]. Throws [IllegalArgumentException] for a
     * non-JPEG/empty payload, [IOException] when the rename fails.
     */
    suspend fun setCover(folder: File, staged: File): Unit = withContext(Dispatchers.IO) {
        require(staged.isFile && staged.length() > 0L && PhotoMetadata.looksLikeJpeg(staged)) {
            "Cover image is empty or not a JPEG."
        }
        val temp = File(folder, ".cover-${UUID.randomUUID()}.tmp")
        try {
            staged.copyTo(temp)
            if (!temp.renameTo(File(folder, COVER_FILE_NAME))) {
                throw IOException("Could not store the cover in ${folder.path}")
            }
            staged.delete()
        } finally {
            temp.delete()
        }
    }

    /** Deletes the folder's cover file; a missing cover is a no-op. */
    suspend fun removeCover(folder: File): Unit = withContext(Dispatchers.IO) {
        coverFile(folder)?.delete()
    }

    // endregion

    // region containers

    /**
     * Creates a sanitized new folder inside [parent]; a name collision
     * with an existing entry silently yields the " (2)" variant instead
     * of an error (the filesystem, like a file manager, is the authority).
     */
    suspend fun createFolder(parent: File, name: String): File = withContext(Dispatchers.IO) {
        val base = Filenames.sanitize(name)
        val dir = File(parent, Filenames.uniqueName(parent, base, ext = ""))
        if (!dir.mkdir()) throw IOException("Could not create ${dir.path}")
        dir
    }

    /**
     * Renames [folder] to a sanitized [newName] collision-safely; returns
     * the folder's new `File` (the same instance when nothing changed).
     */
    suspend fun renameFolder(folder: File, newName: String): File = withContext(Dispatchers.IO) {
        val parent = folder.parentFile ?: return@withContext folder
        val base = Filenames.sanitize(newName)
        val candidateName = Filenames.uniqueName(parent, base, ext = "", ignoring = folder.name)
        val target = File(parent, candidateName)
        if (target == folder || (!target.exists() && folder.renameTo(target))) target
        else folder
    }

    /**
     * Moves [folder] under [newParent]. Refuses a vanished parent and a
     * move into itself or its own subtree; a name clash at the destination
     * resolves to the " (2)" variant.
     */
    suspend fun moveFolder(folder: File, newParent: File): MoveResult =
        withContext(Dispatchers.IO) {
            if (!newParent.isDirectory) return@withContext MoveResult.Failed
            val folderPath = folder.absolutePath.trimEnd('/')
            val parentPath = newParent.absolutePath.trimEnd('/')
            if (parentPath == folderPath || parentPath.startsWith("$folderPath/")) {
                return@withContext MoveResult.Failed
            }
            if (folder.parentFile?.absolutePath == newParent.absolutePath) {
                return@withContext MoveResult.Success(folder)
            }
            val candidateName = Filenames.uniqueName(
                newParent,
                folder.name,
                ext = "",
                ignoring = if (folder.parentFile == newParent) folder.name else null,
            )
            val target = File(newParent, candidateName)
            if (folder.renameTo(target)) MoveResult.Success(target) else MoveResult.Failed
        }

    /** Deletes [folder] with everything inside it. */
    suspend fun deleteFolder(folder: File): Boolean = withContext(Dispatchers.IO) {
        folder.deleteRecursively()
    }

    /**
     * Sizes of [folder]'s subtree for the delete confirmation:
     * (child containers excluding [folder] itself, photos in [folder] and
     * in every descendant; covers are not counted as photos).
     */
    suspend fun subtreeCounts(folder: File): Pair<Int, Int> = withContext(Dispatchers.IO) {
        var containers = 0
        var photos = 0
        val stack = ArrayDeque<File>()
        stack.addLast(folder)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = listContainers(dir)
            containers += children.size
            photos += listPhotos(dir).size
            stack.addAll(children)
        }
        containers to photos
    }

    /** [folder] itself plus every descendant folder path (cycle-safe move picker). */
    suspend fun descendantFolderPaths(folder: File): List<String> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<String>()
        val stack = ArrayDeque<File>()
        stack.addLast(folder)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            paths += dir.absolutePath
            stack.addAll(listContainers(dir))
        }
        paths
    }

    // endregion

    // region photos

    /**
     * Adds the staged image [staged] to [folder] as a permanent photo:
     * final name = [sanitize]d [description] + ".jpg" (collision-suffixed),
     * copied into a `.pending-<uuid>.jpg` temp inside the folder, stamped
     * with description/note (and DateTimeOriginal only when the file
     * lacks one — so camera picks keep their real capture time), then
     * renamed into place; [staged] is deleted on success. A non-JPEG or
     * unrewritable payload yields [AddOutcome.InvalidImage] and leaves no
     * temp file behind.
     */
    suspend fun addPhoto(
        folder: File,
        staged: File,
        description: String,
        note: String?,
    ): AddOutcome = withContext(Dispatchers.IO) {
        val trimmed = description.trim()
        if (trimmed.isEmpty()) return@withContext AddOutcome.BlankDescription
        if (!staged.isFile || staged.length() == 0L || !PhotoMetadata.looksLikeJpeg(staged)) {
            return@withContext AddOutcome.InvalidImage
        }
        val temp = File(folder, ".pending-${UUID.randomUUID()}.jpg")
        try {
            staged.copyTo(temp)
            PhotoMetadata.write(
                temp,
                description = trimmed,
                note = note?.trim()?.ifEmpty { null },
                setDateTakenIfMissing = staged.lastModified().takeIf { it > 0L },
            )
            val finalName = Filenames.uniqueName(
                folder,
                Filenames.sanitize(trimmed),
                ext = "jpg",
            )
            if (!temp.renameTo(File(folder, finalName))) {
                return@withContext AddOutcome.InvalidImage
            }
            staged.delete()
            AddOutcome.Success
        } catch (exception: IOException) {
            AddOutcome.InvalidImage
        } catch (exception: RuntimeException) {
            // ExifInterface rejects payloads it cannot parse as images.
            AddOutcome.InvalidImage
        } finally {
            temp.delete()
        }
    }

    /**
     * Rewrites a photo's EXIF description/note and, when the sanitized
     * description differs from the current base name, renames the file
     * collision-safely. The taken date is never touched here. [file] is
     * the CURRENT file; the caller re-looks it up from the cache after the
     * (possibly renamed) result.
     */
    suspend fun updatePhotoMetadata(
        file: File,
        newDescription: String,
        newNote: String?,
    ): UpdateOutcome = withContext(Dispatchers.IO) {
        val trimmed = newDescription.trim()
        if (trimmed.isEmpty()) return@withContext UpdateOutcome.BlankDescription
        if (!file.isFile) return@withContext UpdateOutcome.InvalidImage
        try {
            PhotoMetadata.write(
                file,
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
        if (desired == file.nameWithoutExtension) return@withContext UpdateOutcome.Success
        val parent = file.parentFile ?: return@withContext UpdateOutcome.Success
        val target = File(
            parent,
            Filenames.uniqueName(parent, desired, ext = "jpg", ignoring = file.name),
        )
        if (target != file && !file.renameTo(target)) UpdateOutcome.InvalidImage
        else UpdateOutcome.Success
    }

    /** Deletes a photo file; a vanished file is a no-op. */
    suspend fun deletePhoto(file: File): Boolean = withContext(Dispatchers.IO) {
        !file.exists() || file.delete()
    }

    /**
     * Moves every file of [files] into [targetFolder], collision-suffixing
     * each name; returns the number of files that ended up in the target.
     */
    suspend fun movePhotos(files: List<File>, targetFolder: File): Int =
        withContext(Dispatchers.IO) {
            var moved = 0
            for (file in files) {
                if (!file.isFile) continue
                if (file.parentFile?.absolutePath == targetFolder.absolutePath) {
                    moved++
                    continue
                }
                val name = Filenames.uniqueName(
                    targetFolder,
                    file.nameWithoutExtension,
                    ext = file.extension.ifEmpty { "jpg" },
                )
                if (file.renameTo(File(targetFolder, name))) moved++
            }
            moved
        }

    /** A photo's taken time: EXIF DateTimeOriginal, else the file's last modification. */
    suspend fun dateTaken(file: File): Long = withContext(Dispatchers.IO) {
        PhotoMetadata.read(file).dateTaken
            ?: file.lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()
    }

    // endregion

    // region staging (app cache dir — bytes waiting for a folder + description)

    /** Writes captured [bytes] verbatim into `cacheDir/pending/<uuid>.jpg` and returns it. */
    suspend fun stageBytes(bytes: ByteArray): File = withContext(Dispatchers.IO) {
        val dir = pendingDir()
        val file = File(dir, "${UUID.randomUUID()}.jpg")
        file.writeBytes(bytes)
        file
    }

    /**
     * Copies the content-uri bytes verbatim into a pending file (gallery
     * picks are copies — the originals stay in the gallery); null when the
     * uri cannot be opened or yields nothing.
     */
    suspend fun stageUri(uri: Uri): File? = withContext(Dispatchers.IO) {
        val file = File(pendingDir(), "${UUID.randomUUID()}.jpg")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: return@withContext null
            input.use { source -> file.outputStream().use { target -> source.copyTo(target) } }
            if (file.length() == 0L) {
                file.delete()
                null
            } else {
                file
            }
        } catch (exception: IOException) {
            file.delete()
            null
        }
    }

    /** Discards a staged file (cancelled queue entry, failed save, …). */
    suspend fun unstage(file: File): Unit = withContext(Dispatchers.IO) {
        file.delete()
    }

    private fun pendingDir(): File = File(context.cacheDir, "pending").apply { mkdirs() }

    // endregion

    /** Result of a container move attempt. */
    sealed interface MoveResult {
        /** The folder now lives under the target as [moved]. */
        data class Success(val moved: File) : MoveResult

        /** Vanished/cyclic/forbidden target — disk untouched. */
        data object Failed : MoveResult
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

    companion object {
        /** Folder name under the external-storage root, e.g. `/sdcard/PhotoInventory`. */
        const val ROOT_FOLDER_NAME = "PhotoInventory"

        /** A container's cover lives in this fixed file inside its folder. */
        const val COVER_FILE_NAME = "_cover.jpg"

        /** Syncthing-style conflict marker: `name.sync-conflict-YYYYMMDD-HHMMSS-DEV`. */
        private val CONFLICT_REGEX = Regex("""\.sync-conflict-\d{8}-\d{6}-\w+""")
    }
}
