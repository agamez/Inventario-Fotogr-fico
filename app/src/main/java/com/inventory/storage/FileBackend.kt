package com.inventory.storage

import android.content.Context
import android.os.Environment
import com.inventory.storage.PhotoMetadata.looksLikeJpeg
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The direct-`File` backend: the inventory root is the plain folder
 * `/sdcard/PhotoInventory` and every entry is addressed as
 * `File(root, relPath)`. Requires All Files Access (see [StorageAccess]) —
 * it is the advanced mode behind the [SafBackend] default.
 *
 * Behaviour mirrors what the app did before dual-mode storage existed:
 * hidden (dot-prefixed) entries are never listed, collisions resolve to
 * Filenames' " (2)" variants, and every write lands through a temp file
 * inside the destination folder that is renamed into place.
 */
@Singleton
class FileBackend @Inject constructor(
    @ApplicationContext private val context: Context,
) : StorageBackend {

    /** The inventory root folder; created lazily by [ensureRoot]. */
    val root: File = File(Environment.getExternalStorageDirectory(), ROOT_FOLDER_NAME)

    /** Whether this backend may currently read and write [root]. */
    fun hasAccess(): Boolean = StorageAccess.hasStorageAccess(context)

    /** Creates [root] if needed; false when storage is inaccessible or mkdirs failed. */
    suspend fun ensureRoot(): Boolean = withContext(Dispatchers.IO) {
        hasAccess() && (root.isDirectory || root.mkdirs())
    }

    /** Resolves a relative path ("" = the root itself) against [root]. */
    private fun fileOf(rel: String): File = if (rel.isEmpty()) root else File(root, rel)

    private fun File.children(parentRel: String): List<File> =
        listFiles()?.filter { !it.name.startsWith(".") }?.toList() ?: emptyList()

    private fun File.toEntry(parentRel: String) = BackendEntry(
        relPath = joinRel(parentRel, name),
        name = name,
        isConflict = isConflictName(name),
        lastModified = lastModified(),
        size = length(),
    )

    override suspend fun listContainers(relPath: String): List<BackendEntry> =
        withContext(Dispatchers.IO) {
            fileOf(relPath).children(relPath)
                .filter { it.isDirectory }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
                .map { it.toEntry(relPath) }
        }

    override suspend fun listPhotos(relPath: String): List<BackendEntry> =
        withContext(Dispatchers.IO) {
            fileOf(relPath).children(relPath)
                .filter {
                    it.isFile &&
                        !it.name.equals(COVER_FILE_NAME, ignoreCase = true) &&
                        (it.extension.equals("jpg", ignoreCase = true) ||
                            it.extension.equals("jpeg", ignoreCase = true))
                }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
                .map { it.toEntry(relPath) }
        }

    override suspend fun exists(relPath: String): Boolean = withContext(Dispatchers.IO) {
        fileOf(relPath).exists()
    }

    override suspend fun readPhotoMeta(relPath: String): PhotoMetadata.PhotoMeta? =
        withContext(Dispatchers.IO) {
            val file = fileOf(relPath)
            if (file.isFile) PhotoMetadata.read(file) else null
        }

    override suspend fun readPhotoBytes(relPath: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = fileOf(relPath)
        runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
    }

    override suspend fun writePhotoBytes(relPath: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            val target = fileOf(relPath)
            val parent = target.parentFile ?: throw IOException("Cannot write to $relPath")
            if (!parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Could not create ${parent.path}")
            }
            val temp = File(parent, ".pending-${UUID.randomUUID()}.jpg")
            try {
                temp.writeBytes(bytes)
                target.takeIf { it.exists() }?.delete()
                if (!temp.renameTo(target)) {
                    throw IOException("Could not store $relPath")
                }
            } finally {
                temp.delete()
            }
        }

    override suspend fun createFolder(parentRel: String, name: String): String =
        withContext(Dispatchers.IO) {
            val parent = fileOf(parentRel)
            val dir = File(parent, Filenames.uniqueName(parent, name, ext = ""))
            if (!dir.mkdir() && !dir.isDirectory) throw IOException("Could not create ${dir.path}")
            joinRel(parentRel, dir.name)
        }

    override suspend fun renameFolder(rel: String, newName: String): String =
        withContext(Dispatchers.IO) {
            val folder = fileOf(rel)
            val parent = folder.parentFile ?: return@withContext rel
            val candidateName = Filenames.uniqueName(parent, newName, ext = "", ignoring = folder.name)
            val target = File(parent, candidateName)
            if (target == folder || (!target.exists() && folder.renameTo(target))) {
                joinRel(parentOf(rel), target.name)
            } else {
                rel
            }
        }

    override suspend fun moveFolder(rel: String, newParentRel: String): Boolean =
        withContext(Dispatchers.IO) {
            val folder = fileOf(rel)
            val newParent = fileOf(newParentRel)
            if (!folder.isDirectory || !newParent.isDirectory) return@withContext false
            if (folder.parentFile?.absolutePath == newParent.absolutePath) return@withContext true
            val candidateName = Filenames.uniqueName(newParent, folder.name, ext = "")
            folder.renameTo(File(newParent, candidateName))
        }

    override suspend fun deleteFolderRecursive(rel: String): Unit = withContext(Dispatchers.IO) {
        fileOf(rel).deleteRecursively()
        Unit
    }

    override suspend fun deleteFile(rel: String): Boolean = withContext(Dispatchers.IO) {
        val file = fileOf(rel)
        !file.exists() || file.delete()
    }

    override suspend fun coverModel(rel: String): Any? = withContext(Dispatchers.IO) {
        fileOf(joinRel(rel, COVER_FILE_NAME)).takeIf { it.isFile }
    }

    override suspend fun photoModel(rel: String): Any? = withContext(Dispatchers.IO) {
        fileOf(rel).takeIf { it.isFile }
    }

    override suspend fun setCover(folderRel: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            require(bytes.isNotEmpty() && looksLikeJpeg(bytes)) { "Cover image is empty or not a JPEG." }
            writePhotoBytes(joinRel(folderRel, COVER_FILE_NAME), bytes)
        }

    override suspend fun removeCover(folderRel: String): Unit = withContext(Dispatchers.IO) {
        deleteFile(joinRel(folderRel, COVER_FILE_NAME))
        Unit
    }

    override suspend fun hasCover(rel: String): Boolean = withContext(Dispatchers.IO) {
        fileOf(joinRel(rel, COVER_FILE_NAME)).isFile
    }

    override suspend fun lastModifiedAndSize(rel: String): Pair<Long, Long>? =
        withContext(Dispatchers.IO) {
            val file = fileOf(rel)
            if (file.exists()) file.lastModified() to file.length() else null
        }

    /** The File mode needs no extra locator — the relative path IS the address. */
    override suspend fun documentUriFor(rel: String): String? = null
}
