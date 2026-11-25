package com.inventory.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Read/write surface of the disposable index. Everything is keyed on the
 * relative path (root = `""`); [replaceAll] rebuilds the whole table in
 * one transaction after each filesystem scan, so live Flow readers
 * converge without per-mutation bookkeeping — renames and moves need no
 * path rewriting, a rescan simply re-keys the world.
 *
 * LIKE patterns embed user-controlled text (folder names in paths, search
 * queries), so every such parameter goes through [escapeForLike] and every
 * pattern uses `ESCAPE '\'` — a literal `%` or `_` in a name can never act
 * as a wildcard. [observeChildren] and [observePhotosIn] take a nullable
 * `parentRel`: SQL `IS` matches NULL rows (direct children of the root)
 * when the bound value is null, which plain `=` never would.
 */
@Dao
abstract class CacheDao {

    /** Live container children of [parentRel] (null = direct children of root). */
    @Query(
        "SELECT * FROM index_entries WHERE parentRel IS :parentRel AND kind = 0 " +
            "ORDER BY name COLLATE NOCASE",
    )
    abstract fun observeChildren(parentRel: String?): Flow<List<CacheEntry>>

    /** Live item children of [parentRel] (kind 2), name-sorted. */
    @Query(
        "SELECT * FROM index_entries WHERE parentRel IS :parentRel AND kind = 2 " +
            "ORDER BY name COLLATE NOCASE",
    )
    abstract fun observeChildItems(parentRel: String?): Flow<List<CacheEntry>>

    /** Live count of item children of [parentRel] (kind 2). */
    @Query("SELECT COUNT(*) FROM index_entries WHERE parentRel IS :parentRel AND kind = 2")
    abstract fun observeChildItemCount(parentRel: String?): Flow<Int>

    /**
     * DIRECT child counts per kind of [parentRel] — only the kinds actually
     * present yield a row. Complements [observeAllChildCounts] when the
     * caller needs the three-way container/item/photo split.
     */
    @Query(
        "SELECT kind AS kind, COUNT(*) AS count FROM index_entries " +
            "WHERE parentRel IS :parentRel GROUP BY kind",
    )
    abstract fun observeChildKindCounts(parentRel: String?): Flow<List<KindCount>>

    /**
     * Raw variant used only through [observePhotosIn] (which escapes the
     * query); public because Room requires annotated DAO methods to be
     * visible. Live photo rows inside exactly one folder (null [parentRel]
     * = photos in the root).
     */
    @Query(
        "SELECT * FROM index_entries WHERE kind = 1 AND parentRel IS :parentRel AND " +
            "(:query = '' OR description LIKE '%' || :query || '%' ESCAPE '\\' OR " +
            "note LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY dateTaken DESC",
    )
    abstract fun observePhotosInEscaped(
        parentRel: String?,
        query: String,
    ): Flow<List<CacheEntry>>

    /**
     * Raw variant used only through [observePhotosUnder]; live photo rows
     * anywhere below the (escaped) relative folder-path prefix.
     */
    @Query(
        "SELECT * FROM index_entries WHERE kind = 1 AND " +
            "relPath LIKE :folderEsc || '/%' ESCAPE '\\' AND " +
            "(:query = '' OR description LIKE '%' || :query || '%' ESCAPE '\\' OR " +
            "note LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY dateTaken DESC",
    )
    abstract fun observePhotosUnderEscaped(
        folderEsc: String,
        query: String,
    ): Flow<List<CacheEntry>>

    /** Photos of exactly one folder; [query] may be blank, wildcards are literal. */
    fun observePhotosIn(parentRel: String?, query: String): Flow<List<CacheEntry>> =
        observePhotosInEscaped(parentRel, escapeForLike(query))

    /**
     * Photos of one item (kind 1 rows whose `parentRel` is the item's
     * relPath) — exactly what the item screen lists; [query] may be blank.
     */
    fun observeItemPhotos(itemRel: String, query: String): Flow<List<CacheEntry>> =
        observePhotosIn(itemRel, query)

    /** Photos of the folder at [folderRel] and its whole subtree; [query] may be blank. */
    fun observePhotosUnder(folderRel: String, query: String): Flow<List<CacheEntry>> =
        observePhotosUnderEscaped(escapeForLike(folderRel), escapeForLike(query))

    /** Every container row, path-sorted — the data behind the move pickers. */
    @Query("SELECT * FROM index_entries WHERE kind = 0 ORDER BY relPath COLLATE NOCASE")
    abstract fun observeAllFolders(): Flow<List<CacheEntry>>

    /** Every item row, path-sorted — the data behind the "add to element" pickers. */
    @Query("SELECT * FROM index_entries WHERE kind = 2 ORDER BY relPath COLLATE NOCASE")
    abstract fun observeAllItems(): Flow<List<CacheEntry>>

    /** One item row by relative path; null when [relPath] is not an item. */
    @Query("SELECT * FROM index_entries WHERE kind = 2 AND relPath = :relPath")
    abstract suspend fun getItem(relPath: String): CacheEntry?

    /** Live item row by relative path; null while/after [relPath] is not an item. */
    @Query("SELECT * FROM index_entries WHERE kind = 2 AND relPath = :relPath")
    abstract fun observeItem(relPath: String): Flow<CacheEntry?>

    /** Direct sub-counts for every container row at once (see [ChildCounts]). */
    @Query(
        "SELECT c.relPath AS relPath, " +
            "(SELECT COUNT(*) FROM index_entries s WHERE s.parentRel = c.relPath AND s.kind = 0) AS folders, " +
            "(SELECT COUNT(*) FROM index_entries p WHERE p.parentRel = c.relPath AND p.kind = 1) AS photos, " +
            "(SELECT COUNT(*) FROM index_entries i WHERE i.parentRel = c.relPath AND i.kind = 2) AS items " +
            "FROM index_entries c WHERE c.kind = 0",
    )
    abstract fun observeAllChildCounts(): Flow<List<ChildCounts>>

    /** Live global photo search over descriptions and notes, capped at 100 rows. */
    @Query(
        "SELECT * FROM index_entries WHERE kind = 1 AND " +
            "(:query = '' OR description LIKE '%' || :query || '%' ESCAPE '\\' OR " +
            "note LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY dateTaken DESC LIMIT 100",
    )
    abstract fun searchPhotosEscaped(query: String): Flow<List<CacheEntry>>

    /** Global photo search; [query] may be blank to browse everything. */
    fun searchPhotos(query: String): Flow<List<CacheEntry>> =
        searchPhotosEscaped(escapeForLike(query))

    /** Raw variant used only through [searchItems] (which escapes the query). */
    @Query(
        "SELECT * FROM index_entries WHERE kind = 2 AND " +
            "(:query = '' OR name LIKE '%' || :query || '%' ESCAPE '\\' OR " +
            "note LIKE '%' || :query || '%' ESCAPE '\\') " +
            "ORDER BY name COLLATE NOCASE LIMIT 100",
    )
    abstract fun searchItemsEscaped(query: String): Flow<List<CacheEntry>>

    /** Global item search by element name and note, capped at 100 rows. */
    fun searchItems(query: String): Flow<List<CacheEntry>> =
        searchItemsEscaped(escapeForLike(query))

    /** Live count of sync-conflicted entries (banner data). */
    @Query("SELECT COUNT(*) FROM index_entries WHERE isConflict != 0")
    abstract fun countConflicts(): Flow<Int>

    /** Snapshot of the whole table — the scanner's reuse source. */
    @Query("SELECT * FROM index_entries")
    abstract suspend fun allEntries(): List<CacheEntry>

    @Query("SELECT * FROM index_entries WHERE relPath = :relPath")
    abstract suspend fun getByRelPath(relPath: String): CacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertAll(entries: List<CacheEntry>)

    @Query("DELETE FROM index_entries WHERE relPath IN (:relPaths)")
    abstract suspend fun deleteRelPaths(relPaths: List<String>)

    @Query(
        "DELETE FROM index_entries WHERE relPath = :relPath OR " +
            "relPath LIKE :relPathEsc || '/%' ESCAPE '\\'",
    )
    abstract suspend fun deleteByRelPrefixQuery(relPath: String, relPathEsc: String)

    /** Drops [prefix] and everything below it (standalone cleanup helper). */
    suspend fun deleteByRelPrefix(prefix: String) =
        deleteByRelPrefixQuery(prefix, escapeForLike(prefix))

    @Query("DELETE FROM index_entries")
    abstract suspend fun clear()

    /** Full rebuild of the table in a single transaction: readers see old or new, never empty. */
    @Transaction
    open suspend fun replaceAll(entries: List<CacheEntry>) {
        clear()
        upsertAll(entries)
    }

    companion object {
        /** Makes user/path text safe for `LIKE … ESCAPE '\'` patterns. */
        fun escapeForLike(raw: String): String = raw
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
    }
}

/**
 * One container with its DIRECT sub-counts (immediate child folders,
 * immediate child photos and immediate child items) — the
 * "n containers · m photos" line of a row. Photos inside an item are
 * counted by the item ([CacheEntry.itemPhotoCount]), never by its
 * container, so nothing is shown twice.
 */
data class ChildCounts(
    val relPath: String,
    val folders: Int,
    val photos: Int,
    val items: Int,
)

/** One row of [CacheDao.observeChildKindCounts]: entries of one kind directly inside a folder. */
data class KindCount(
    val kind: Int,
    val count: Int,
)
