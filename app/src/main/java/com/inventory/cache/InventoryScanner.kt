package com.inventory.cache

import com.inventory.storage.InventoryStore
import com.inventory.storage.PhotoMetadata
import com.inventory.storage.ScanState
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Rebuilds [CacheDao]'s table from the filesystem. The tree walk mirrors
 * `/sdcard/PhotoInventory` entry by entry; each row is keyed by absolute
 * path and carries stat() data plus — for photos — the EXIF triple.
 *
 * Strategy, deliberately simple (correctness over cleverness): every scan
 * collects the full new row list and swaps it in with [CacheDao.replaceAll]
 * in one transaction. EXIF is only re-parsed for files that are new or
 * whose (lastModified, size) changed since the previous scan, so a rescan
 * over an untouched 500-photo tree is a bunch of cheap stats; renaming a
 * subtree with an external tool re-parses exactly that subtree on the next
 * scan (the path key changed). App-side mutations do not touch the DAO at
 * all — they just call [rescan] and the cache converges.
 *
 * [rescan] is Mutex-guarded: concurrent calls coalesce into at most one
 * follow-up pass instead of racing walks.
 */
@Singleton
class InventoryScanner @Inject constructor(
    private val store: InventoryStore,
    private val dao: CacheDao,
) {

    private val mutex = Mutex()

    @Volatile
    private var scanning = false

    @Volatile
    private var rerunRequested = false

    private val _scanState = MutableStateFlow(ScanState())

    /** Full scanner status: the `scanning` flag plus the last completed scan time. */
    val scanState: StateFlow<ScanState> = _scanState.asStateFlow()

    /**
     * One filesystem walk from the root. Skipped (no cache wipe!) when
     * storage is inaccessible or the root does not exist yet — a mounted
     * volume that is briefly gone must not empty the UI.
     */
    suspend fun rescan() {
        if (scanning) {
            rerunRequested = true
            return
        }
        mutex.withLock {
            scanning = true
            _scanState.update { it.copy(scanning = true) }
            try {
                do {
                    rerunRequested = false
                    scanOnce()
                } while (rerunRequested)
            } finally {
                scanning = false
                _scanState.update { it.copy(scanning = false, lastFullScan = System.currentTimeMillis()) }
            }
        }
    }

    private suspend fun scanOnce() {
        if (!store.hasAccess() || !store.root.isDirectory) return
        val previous = dao.allEntries().associateBy(CacheEntry::path)
        val rows = withContext(Dispatchers.IO) { buildRows(previous) }
        dao.replaceAll(rows)
    }

    private fun buildRows(previous: Map<String, CacheEntry>): List<CacheEntry> {
        val rows = ArrayList<CacheEntry>()
        val stack = ArrayDeque<Pair<File, String?>>()
        stack.addLast(store.root to null)
        while (stack.isNotEmpty()) {
            val (dir, parentPath) = stack.removeLast()
            for (child in store.listContainers(dir)) {
                rows += containerRow(child, parentPath)
                stack.addLast(child to child.absolutePath)
            }
            for (photo in store.listPhotos(dir)) {
                rows += photoRow(photo, parentPath, previous)
            }
        }
        return rows
    }

    private fun containerRow(folder: File, parentPath: String?): CacheEntry = CacheEntry(
        path = folder.absolutePath,
        parentPath = parentPath,
        name = folder.name,
        kind = CacheEntry.KIND_CONTAINER,
        isConflict = store.isConflict(folder.name),
        lastModified = folder.lastModified(),
        // A folder's mtime churns on every child edit; the cover check is
        // one stat, cheap enough to always refresh it.
        hasCover = store.coverFile(folder) != null,
    )

    private fun photoRow(
        file: File,
        parentPath: String?,
        previous: Map<String, CacheEntry>,
    ): CacheEntry {
        val lastModified = file.lastModified()
        val size = file.length()
        val old = previous[file.absolutePath]
        if (old != null &&
            old.kind == CacheEntry.KIND_PHOTO &&
            old.lastModified == lastModified &&
            old.size == size &&
            old.isConflict == store.isConflict(file.name)
        ) {
            // Unchanged file: keep the row, skip the EXIF parse.
            return old
        }
        val meta = PhotoMetadata.read(file)
        return CacheEntry(
            path = file.absolutePath,
            parentPath = parentPath,
            name = file.name,
            kind = CacheEntry.KIND_PHOTO,
            isConflict = store.isConflict(file.name),
            description = meta.description ?: file.nameWithoutExtension,
            note = meta.note,
            dateTaken = meta.dateTaken ?: lastModified,
            lastModified = lastModified,
            size = size,
        )
    }
}
