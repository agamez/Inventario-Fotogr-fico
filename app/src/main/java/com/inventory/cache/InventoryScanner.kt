package com.inventory.cache

import com.inventory.storage.BackendEntry
import com.inventory.storage.InventoryStore
import com.inventory.storage.ScanState
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Rebuilds [CacheDao]'s table from the filesystem THROUGH the
 * [InventoryStore] facade, so one walk serves both backends (SAF tree and
 * direct File access see the same on-disk layout). Each row is keyed by
 * the entry's RELATIVE path and carries stat data plus — for photos — the
 * EXIF triple.
 *
 * Strategy, deliberately simple (correctness over cleverness): every scan
 * collects the full new row list and swaps it in with [CacheDao.replaceAll]
 * in one transaction. EXIF is only re-parsed for entries that are new or
 * whose (lastModified, size) changed since the previous scan, so a rescan
 * over an untouched 500-photo tree is a bunch of cheap listings/stats;
 * renaming a subtree with an external tool re-parses exactly that subtree
 * on the next scan (the relPath key changed). App-side mutations do not
 * touch the DAO at all — they just call [rescan] and the cache converges.
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
     * One filesystem walk from the root. Skipped (no cache wipe!) when the
     * active backend is inaccessible or the root is gone — a mounted
     * volume or picker grant that is briefly away must not empty the UI.
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
        if (!store.hasAccess() || !store.exists("")) return
        val previous = dao.allEntries().associateBy(CacheEntry::relPath)
        dao.replaceAll(buildRows(previous))
    }

    private suspend fun buildRows(previous: Map<String, CacheEntry>): List<CacheEntry> {
        val rows = ArrayList<CacheEntry>()
        val stack = ArrayDeque<Pair<String, String?>>()
        stack.addLast("" to null)
        while (stack.isNotEmpty()) {
            val (dirRel, parentRel) = stack.removeLast()
            for (child in store.listContainers(dirRel)) {
                rows += containerRow(child, parentRel)
                stack.addLast(child.relPath to child.relPath)
            }
            for (photo in store.listPhotos(dirRel)) {
                rows += photoRow(photo, parentRel, previous)
            }
        }
        return rows
    }

    private suspend fun containerRow(entry: BackendEntry, parentRel: String?): CacheEntry = CacheEntry(
        relPath = entry.relPath,
        parentRel = parentRel,
        name = entry.name,
        kind = CacheEntry.KIND_CONTAINER,
        isConflict = entry.isConflict,
        lastModified = entry.lastModified,
        // A folder's mtime churns on every child edit; the cover check is
        // one cheap lookup, enough to always refresh it.
        hasCover = store.hasCover(entry.relPath),
        documentUri = store.documentUriFor(entry.relPath),
    )

    private suspend fun photoRow(
        entry: BackendEntry,
        parentRel: String?,
        previous: Map<String, CacheEntry>,
    ): CacheEntry {
        val old = previous[entry.relPath]
        if (old != null &&
            old.kind == CacheEntry.KIND_PHOTO &&
            old.lastModified == entry.lastModified &&
            old.size == entry.size &&
            old.isConflict == entry.isConflict
        ) {
            // Unchanged file: keep the row, skip the EXIF parse.
            return old
        }
        val meta = store.readPhotoMeta(entry.relPath)
        return CacheEntry(
            relPath = entry.relPath,
            parentRel = parentRel,
            name = entry.name,
            kind = CacheEntry.KIND_PHOTO,
            isConflict = entry.isConflict,
            description = meta?.description ?: entry.name.substringBeforeLast('.', entry.name),
            note = meta?.note,
            dateTaken = meta?.dateTaken ?: entry.lastModified.takeIf { it > 0L },
            lastModified = entry.lastModified,
            size = entry.size,
            documentUri = store.documentUriFor(entry.relPath),
        )
    }
}
