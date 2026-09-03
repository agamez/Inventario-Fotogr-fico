package com.inventory.storage

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import com.inventory.storage.PhotoMetadata.looksLikeJpeg
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The Storage Access Framework backend: the inventory root is a tree the
 * user picked with `ACTION_OPEN_DOCUMENT_TREE` (persisted in
 * [StorageModePrefs]), so this mode needs ZERO runtime permissions.
 *
 * Relative-path ↔ document resolution:
 *
 * - Every `tree/…` content uri is addressed by its document id; for the
 *   ExternalStorageProvider (the overwhelmingly common case, and what
 *   `/sdcard/PhotoInventory` is) the id of a child is the parent's id plus
 *   "/" plus the display name — so `Building/Storage unit` resolves to
 *   `"$rootDocId/Building/Storage unit"` in ONE existence query.
 * - When the joined id does not exist (a provider that does not follow
 *   that layout, a renamed subtree caught between walks) the backend falls
 *   back to WALKING: list each level's children and match DISPLAY_NAME,
 *   memoizing every step.
 * - Results live in an in-memory memo (relPath → uri) populated by
 *   resolution and listing and cleared on every mutation, so a full scan
 *   costs one child query per folder and nothing else.
 *
 * Writes follow the File backend's atomicity: a `.pending-<uuid>.jpg`
 * child is created in the target folder, written through
 * `openOutputStream("wt")` and renamed to its final name (collisions are
 * pre-checked via listing, an existing target is deleted first — rename
 * must therefore always succeed). EXIF rewrites read the whole file into
 * memory, restamp via [PhotoMetadata.withExif] and truncate-write back, so
 * pixels stay byte-identical. Deletion recurses bottom-up explicitly
 * (some providers delete directories recursively, most refuse non-empty
 * ones). `moveDocument` is tried first; providers that reject it fall back
 * to an explicit copy-tree-then-delete.
 */
@Singleton
class SafBackend @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: StorageModePrefs,
) : StorageBackend {

    private val resolver get() = context.contentResolver

    /** relPath → resolved document uri; cleared on every mutation. */
    private val memo = ConcurrentHashMap<String, Uri>()

    /** The picked tree uri, or null when no folder has been granted yet. */
    private fun treeUri(): Uri? = prefs.treeUri()?.let { Uri.parse(it) }

    /** True when a tree is persisted AND its grant is still alive (read+write). */
    fun hasTree(): Boolean {
        val uri = treeUri() ?: return false
        return resolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
    }

    /** Drops the resolution memo (the caller changed the tree behind our back). */
    fun invalidate() {
        memo.clear()
    }

    private data class SafChild(
        val name: String,
        val docId: String,
        val uri: Uri,
        val isDirectory: Boolean,
        val lastModified: Long,
        val size: Long,
    )

    // region resolution

    private fun rootDocId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)

    private fun docUri(tree: Uri, docId: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(tree, docId)

    private suspend fun uriFor(rel: String): Uri? {
        val tree = treeUri() ?: return null
        if (rel.isEmpty()) return docUri(tree, rootDocId(tree)).also { memo[rel] = it }
        memo[rel]?.let { return it }
        val joined = docUri(tree, "${rootDocId(tree)}/$rel")
        if (documentExists(joined)) {
            memo[rel] = joined
            return joined
        }
        return walkResolve(tree, rel)
    }

    private suspend fun documentExists(uri: Uri): Boolean = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
            ?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    /** Last-chance resolution by listing every level; memoizes along the way. */
    private suspend fun walkResolve(tree: Uri, rel: String): Uri? {
        var parentDocId = rootDocId(tree)
        var parentRel = ""
        var current: Uri? = null
        for (segment in rel.split('/')) {
            val child = runCatching { queryChildren(tree, parentDocId) }
                .getOrDefault(emptyList())
                .firstOrNull { it.name == segment }
                ?: return null
            parentDocId = child.docId
            parentRel = joinRel(parentRel, segment)
            memo[parentRel] = child.uri
            current = child.uri
        }
        return current
    }

    private suspend fun queryChildren(tree: Uri, parentDocId: String): List<SafChild> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_SIZE,
        )
        val rows = mutableListOf<SafChild>()
        resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                rows += cursor.toSafChild(tree)
            }
        }
        return rows
    }

    private fun Cursor.toSafChild(tree: Uri): SafChild {
        val docId = getString(0) ?: ""
        return SafChild(
            name = getString(1) ?: docId.substringAfterLast('/'),
            docId = docId,
            uri = docUri(tree, docId),
            isDirectory = getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
            lastModified = if (isNull(3)) 0L else getLong(3),
            size = if (isNull(4)) 0L else getLong(4),
        )
    }

    /** Children of [parentRel], refreshing the memo with their uris. */
    private suspend fun childrenOf(parentRel: String): List<SafChild> {
        val tree = treeUri() ?: return emptyList()
        val parentUri = uriFor(parentRel) ?: return emptyList()
        val children = runCatching {
            queryChildren(tree, DocumentsContract.getDocumentId(parentUri))
        }.getOrDefault(emptyList())
        children.forEach { memo[joinRel(parentRel, it.name)] = it.uri }
        return children
    }

    private suspend fun displayNameOf(uri: Uri): String? = runCatching {
        resolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    // endregion

    // region listing

    override suspend fun listContainers(relPath: String): List<BackendEntry> =
        withContext(Dispatchers.IO) {
            childrenOf(relPath)
                .filter { it.isDirectory && !it.name.startsWith(".") }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
                .map { it.toBackendEntry(relPath) }
        }

    override suspend fun listPhotos(relPath: String): List<BackendEntry> =
        withContext(Dispatchers.IO) {
            childrenOf(relPath)
                .filter { child ->
                    !child.isDirectory &&
                        !child.name.startsWith(".") &&
                        !child.name.equals(COVER_FILE_NAME, ignoreCase = true) &&
                        (child.name.endsWith(".jpg", ignoreCase = true) ||
                            child.name.endsWith(".jpeg", ignoreCase = true))
                }
                .sortedBy { it.name.lowercase(Locale.getDefault()) }
                .map { it.toBackendEntry(relPath) }
        }

    private fun SafChild.toBackendEntry(parentRel: String) = BackendEntry(
        relPath = joinRel(parentRel, name),
        name = name,
        isConflict = isConflictName(name),
        lastModified = lastModified,
        size = size,
    )

    // endregion

    // region reading

    override suspend fun exists(relPath: String): Boolean = withContext(Dispatchers.IO) {
        uriFor(relPath) != null
    }

    override suspend fun readPhotoMeta(relPath: String): PhotoMetadata.PhotoMeta? =
        withContext(Dispatchers.IO) {
            val uri = uriFor(relPath) ?: return@withContext null
            runCatching {
                resolver.openInputStream(uri)?.use { PhotoMetadata.read(it) }
            }.getOrNull()
        }

    override suspend fun readPhotoBytes(relPath: String): ByteArray? = withContext(Dispatchers.IO) {
        val uri = uriFor(relPath) ?: return@withContext null
        runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
    }

    override suspend fun lastModifiedAndSize(rel: String): Pair<Long, Long>? =
        withContext(Dispatchers.IO) {
            val uri = uriFor(rel) ?: return@withContext null
            runCatching {
                resolver.query(
                    uri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_SIZE,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        (if (cursor.isNull(0)) 0L else cursor.getLong(0)) to
                            (if (cursor.isNull(1)) 0L else cursor.getLong(1))
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }

    // endregion

    // region writing

    override suspend fun writePhotoBytes(relPath: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            invalidate()
            try {
                val parentRel = parentOf(relPath)
                val parentUri = uriFor(parentRel)
                    ?: throw IOException("Parent folder of $relPath is gone")
                uriFor(relPath)?.let { stale ->
                    runCatching { DocumentsContract.deleteDocument(resolver, stale) }
                }
                val tempUri = DocumentsContract.createDocument(
                    resolver,
                    parentUri,
                    "image/jpeg",
                    ".pending-${UUID.randomUUID()}.jpg",
                ) ?: throw IOException("Could not stage $relPath")
                try {
                    resolver.openOutputStream(tempUri, "wt")?.use { it.write(bytes) }
                        ?: throw IOException("Could not open $relPath for writing")
                    val renamed = DocumentsContract.renameDocument(resolver, tempUri, nameOf(relPath))
                        ?: throw IOException("Could not name $relPath")
                    if (displayNameOf(renamed) != nameOf(relPath)) {
                        runCatching { DocumentsContract.deleteDocument(resolver, renamed) }
                        throw IOException("Provider altered the name of $relPath")
                    }
                } catch (exception: Exception) {
                    runCatching { DocumentsContract.deleteDocument(resolver, tempUri) }
                    if (exception is IOException) throw exception
                    throw IOException("Could not store $relPath", exception)
                }
            } finally {
                invalidate()
            }
        }

    override suspend fun setCover(folderRel: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            require(bytes.isNotEmpty() && looksLikeJpeg(bytes)) { "Cover image is empty or not a JPEG." }
            writePhotoBytes(joinRel(folderRel, COVER_FILE_NAME), bytes)
        }

    override suspend fun removeCover(folderRel: String): Unit = withContext(Dispatchers.IO) {
        runCatching { deleteFile(joinRel(folderRel, COVER_FILE_NAME)) }
        Unit
    }

    override suspend fun hasCover(rel: String): Boolean = withContext(Dispatchers.IO) {
        uriFor(joinRel(rel, COVER_FILE_NAME)) != null
    }

    // endregion

    // region folders

    override suspend fun createFolder(parentRel: String, name: String): String =
        withContext(Dispatchers.IO) {
            invalidate()
            try {
                val parentUri = uriFor(parentRel)
                    ?: throw IOException("Parent folder $parentRel is gone")
                val taken = childrenOf(parentRel).map { it.name }
                val desired = Filenames.uniqueNameIn(taken.toSet(), name, ext = "")
                val created = DocumentsContract.createDocument(
                    resolver,
                    parentUri,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    desired,
                ) ?: throw IOException("Could not create $desired")
                val actual = displayNameOf(created) ?: desired
                joinRel(parentRel, actual)
            } finally {
                invalidate()
            }
        }

    override suspend fun renameFolder(rel: String, newName: String): String =
        withContext(Dispatchers.IO) {
            invalidate()
            try {
                val parentRel = parentOf(rel)
                val uri = uriFor(rel) ?: return@withContext rel
                if (nameOf(rel) == newName) return@withContext rel
                val taken = childrenOf(parentRel)
                    .filterNot { it.name == nameOf(rel) }
                    .map { it.name }
                    .toSet()
                val desired = Filenames.uniqueNameIn(taken, newName, ext = "")
                val renamed = DocumentsContract.renameDocument(resolver, uri, desired)
                    ?: return@withContext rel
                joinRel(parentRel, displayNameOf(renamed) ?: desired)
            } finally {
                invalidate()
            }
        }

    override suspend fun moveFolder(rel: String, newParentRel: String): Boolean =
        withContext(Dispatchers.IO) {
            invalidate()
            try {
                val source = uriFor(rel) ?: return@withContext false
                val sourceParent = uriFor(parentOf(rel)) ?: return@withContext false
                val targetParent = uriFor(newParentRel) ?: return@withContext false
                val moved = runCatching {
                    DocumentsContract.moveDocument(resolver, source, sourceParent, targetParent)
                }.getOrNull()
                if (moved != null) {
                    true
                } else {
                    copyThenDelete(rel, newParentRel)
                }
            } finally {
                invalidate()
            }
        }

    /** Last resort for providers without move support: byte-copy the tree, then delete. */
    private suspend fun copyThenDelete(rel: String, newParentRel: String): Boolean = runCatching {
        val stack = ArrayDeque<Pair<String, String>>()
        stack.addLast(rel to createFolder(newParentRel, nameOf(rel)))
        while (stack.isNotEmpty()) {
            val (srcDir, dstDir) = stack.removeLast()
            for (photo in listPhotos(srcDir)) {
                val bytes = readPhotoBytes(photo.relPath) ?: continue
                writePhotoBytes(joinRel(dstDir, photo.name), bytes)
            }
            if (hasCover(srcDir)) {
                readPhotoBytes(joinRel(srcDir, COVER_FILE_NAME))?.let { setCover(dstDir, it) }
            }
            for (child in listContainers(srcDir)) {
                stack.addLast(child.relPath to createFolder(dstDir, child.name))
            }
        }
        deleteFolderRecursive(rel)
        true
    }.getOrDefault(false)

    override suspend fun deleteFolderRecursive(rel: String): Unit = withContext(Dispatchers.IO) {
        invalidate()
        try {
            val rootUri = uriFor(rel) ?: return@withContext
            deleteDirBottomUp(rel, rootUri)
        } finally {
            invalidate()
        }
    }

    /** Files first, then child folders (each cleaned the same way), then the folder itself. */
    private suspend fun deleteDirBottomUp(dirRel: String, dirUri: Uri) {
        val subDirs = mutableListOf<Pair<String, Uri>>()
        for (child in childrenOf(dirRel)) {
            if (child.isDirectory) {
                subDirs += joinRel(dirRel, child.name) to child.uri
            } else {
                runCatching { DocumentsContract.deleteDocument(resolver, child.uri) }
            }
        }
        subDirs.forEach { (childRel, childUri) -> deleteDirBottomUp(childRel, childUri) }
        runCatching { DocumentsContract.deleteDocument(resolver, dirUri) }
    }

    override suspend fun deleteFile(rel: String): Boolean = withContext(Dispatchers.IO) {
        invalidate()
        try {
            val uri = uriFor(rel) ?: return@withContext true
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false) &&
                !documentExists(uri)
        } finally {
            invalidate()
        }
    }

    // endregion

    // region models

    override suspend fun coverModel(rel: String): Any? = withContext(Dispatchers.IO) {
        uriFor(joinRel(rel, COVER_FILE_NAME))
    }

    override suspend fun photoModel(rel: String): Any? = withContext(Dispatchers.IO) {
        uriFor(rel)
    }

    override suspend fun documentUriFor(rel: String): String? = withContext(Dispatchers.IO) {
        uriFor(rel)?.toString()
    }

    // endregion
}

/**
 * Grants needed to persist a picked tree across reboots
 * ([InventoryStore.setTreeUri]).
 */
internal val SAF_GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or
    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
