package com.inventory.desktop.index

import com.inventory.storage.BackendEntry
import com.inventory.storage.PhotoMeta
import com.inventory.storage.StorageBackend
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An in-memory index of the whole inventory tree, rebuilt from disk on
 * demand: it is what the desktop UI renders and what search queries run
 * over — the same role the Android app's Room cache plays there. Disk
 * stays the single source of truth: nothing here cannot be re-derived by
 * a rebuild, and EXIF parses are skipped for files whose
 * (path, lastModified, size) key did not change since the last walk, so a
 * Syncthing drop re-parses only the touched photos.
 */
class DesktopIndex(private val backend: StorageBackend) {

    /** One container row: relative path, display name, cover and conflict flags. */
    data class IndexedContainer(
        val relPath: String,
        val name: String,
        val hasCover: Boolean,
        val isConflict: Boolean,
    )

    /** One photo row; [description] falls back to the filename minus extension. */
    data class IndexedPhoto(
        val relPath: String,
        val name: String,
        val parentRel: String,
        val description: String,
        val note: String?,
        val dateTaken: Long?,
        val isConflict: Boolean,
    )

    /**
     * One immutable walk result. [subtreeCounts] maps every folder path to
     * (descendant containers excluding the folder itself, photos in the
     * whole subtree including it) — the numbers the browse rows and the
     * delete confirmation show, exactly like the Android store's
     * `subtreeCounts`.
     */
    data class Snapshot(
        val containersByParent: Map<String, List<IndexedContainer>> = emptyMap(),
        val photosByParent: Map<String, List<IndexedPhoto>> = emptyMap(),
        val folders: Set<String> = setOf(""),
        val subtreeCounts: Map<String, Pair<Int, Int>> = emptyMap(),
        val allPhotos: List<IndexedPhoto> = emptyList(),
        val conflictCount: Int = 0,
        val builtAt: Long = 0L,
    )

    private val mutex = Mutex()

    /** relPath -> "lastModified:size" key of the last EXIF parse. */
    private val changeKeys = mutableMapOf<String, String>()

    /** relPath -> the EXIF triple behind [changeKeys]. */
    private val parsedMeta = mutableMapOf<String, Triple<String?, String?, Long?>>()

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /**
     * Walks the whole tree from the storage root and publishes a new
     * [Snapshot]. Concurrent calls serialize on an internal mutex, so
     * watcher events and explicit refreshes never interleave half-walks.
     */
    suspend fun rebuild() = mutex.withLock {
        _scanning.value = true
        try {
            val containers = mutableMapOf<String, MutableList<IndexedContainer>>()
            val photos = mutableMapOf<String, MutableList<IndexedPhoto>>()
            val folders = mutableSetOf("")
            val seenKeys = mutableMapOf<String, String>()
            var conflicts = 0
            walk("", containers, photos, folders, seenKeys) { conflicts++ }
            changeKeys.keys.retainAll(seenKeys.keys)
            parsedMeta.keys.retainAll(seenKeys.keys)
            val sortedContainers = containers.mapValues { (_, rows) ->
                rows.sortedBy { it.name.lowercase(Locale.getDefault()) }
            }
            val sortedPhotos = photos.mapValues { (_, rows) ->
                rows.sortedBy { it.name.lowercase(Locale.getDefault()) }
            }
            _snapshot.value = Snapshot(
                containersByParent = sortedContainers,
                photosByParent = sortedPhotos,
                folders = folders,
                subtreeCounts = computeSubtreeCounts(folders, sortedContainers, sortedPhotos),
                allPhotos = sortedPhotos.values.flatten()
                    .sortedBy { it.relPath.lowercase(Locale.getDefault()) },
                conflictCount = conflicts,
                builtAt = System.currentTimeMillis(),
            )
        } finally {
            _scanning.value = false
        }
    }

    private suspend fun walk(
        rel: String,
        containers: MutableMap<String, MutableList<IndexedContainer>>,
        photos: MutableMap<String, MutableList<IndexedPhoto>>,
        folders: MutableSet<String>,
        seenKeys: MutableMap<String, String>,
        onConflict: () -> Unit,
    ) {
        for (entry in backend.listContainers(rel)) {
            if (entry.isConflict) onConflict()
            containers.getOrPut(rel) { mutableListOf() }.add(
                IndexedContainer(
                    relPath = entry.relPath,
                    name = entry.name,
                    hasCover = backend.hasCover(entry.relPath),
                    isConflict = entry.isConflict,
                ),
            )
            folders += entry.relPath
            walk(entry.relPath, containers, photos, folders, seenKeys, onConflict)
        }
        for (entry in backend.listPhotos(rel)) {
            if (entry.isConflict) onConflict()
            photos.getOrPut(rel) { mutableListOf() }.add(indexPhoto(entry, seenKeys))
        }
    }

    /** The photo row for [entry], reusing the cached EXIF triple when unchanged. */
    private suspend fun indexPhoto(entry: BackendEntry, seenKeys: MutableMap<String, String>): IndexedPhoto {
        val key = "${entry.lastModified}:${entry.size}"
        seenKeys[entry.relPath] = key
        val cached = if (changeKeys[entry.relPath] == key) parsedMeta[entry.relPath] else null
        val meta = cached ?: backend.readPhotoMeta(entry.relPath)?.let { triple(it) }?.also { parsed ->
            changeKeys[entry.relPath] = key
            parsedMeta[entry.relPath] = parsed
        }
        return IndexedPhoto(
            relPath = entry.relPath,
            name = entry.name,
            parentRel = entry.relPath.substringBeforeLast('/', ""),
            description = meta?.first?.trim()?.ifEmpty { null }
                ?: entry.name.substringBeforeLast('.', entry.name),
            note = meta?.second,
            dateTaken = meta?.third ?: entry.lastModified.takeIf { it > 0L },
            isConflict = entry.isConflict,
        )
    }

    /** (child containers excluding self, subtree photos) for every folder. */
    private fun computeSubtreeCounts(
        folders: Set<String>,
        containers: Map<String, List<IndexedContainer>>,
        photos: Map<String, List<IndexedPhoto>>,
    ): Map<String, Pair<Int, Int>> {
        val result = mutableMapOf<String, Pair<Int, Int>>()

        fun accumulate(rel: String): Pair<Int, Int> = result.getOrPut(rel) {
            var childFolders = 0
            var subtreePhotos = photos[rel]?.size ?: 0
            for (child in containers[rel].orEmpty()) {
                val (nestedFolders, nestedPhotos) = accumulate(child.relPath)
                childFolders += 1 + nestedFolders
                subtreePhotos += nestedPhotos
            }
            childFolders to subtreePhotos
        }

        for (folder in folders) accumulate(folder)
        return result
    }

    /** Photos matching [query] in the folder at [folderRel] (or its subtree). */
    fun photosIn(
        snapshot: Snapshot,
        folderRel: String,
        query: String,
        includeSubtree: Boolean,
    ): List<IndexedPhoto> {
        val candidates = when {
            !includeSubtree -> snapshot.photosByParent[folderRel].orEmpty()
            folderRel.isEmpty() -> snapshot.allPhotos
            else -> snapshot.allPhotos.filter {
                it.parentRel == folderRel || it.parentRel.startsWith("$folderRel/")
            }
        }
        return filterPhotos(candidates, query)
    }

    /** Global search over every indexed photo (date-descending, capped like Android). */
    fun searchAll(snapshot: Snapshot, query: String): List<IndexedPhoto> =
        filterPhotos(snapshot.allPhotos, query)
            .sortedByDescending { it.dateTaken ?: 0L }
            .take(MAX_SEARCH_RESULTS)

    /** Case-insensitive substring match over description, note and relative path. */
    private fun filterPhotos(photos: List<IndexedPhoto>, query: String): List<IndexedPhoto> {
        val trimmed = query.trim().lowercase(Locale.getDefault())
        if (trimmed.isEmpty()) return photos
        return photos.filter { photo ->
            photo.description.lowercase(Locale.getDefault()).contains(trimmed) ||
                photo.note?.lowercase(Locale.getDefault())?.contains(trimmed) == true ||
                photo.relPath.lowercase(Locale.getDefault()).contains(trimmed)
        }
    }

    private fun triple(meta: PhotoMeta) = Triple(meta.description, meta.note, meta.dateTaken)

    companion object {
        /** Same result cap the Android global search applies. */
        const val MAX_SEARCH_RESULTS = 100
    }
}
