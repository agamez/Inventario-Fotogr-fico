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

    /** Photos of the folder at [folderRel] and its whole subtree; [query] may be blank. */
    fun observePhotosUnder(folderRel: String, query: String): Flow<List<CacheEntry>> =
        observePhotosUnderEscaped(escapeForLike(folderRel), escapeForLike(query))

    /** Every container row, path-sorted — the data behind the move pickers. */
    @Query("SELECT * FROM index_entries WHERE kind = 0 ORDER BY relPath COLLATE NOCASE")
    abstract fun observeAllFolders(): Flow<List<CacheEntry>>

    /** Direct sub-counts for every container row at once (see [ChildCounts]). */
    @Query(
        "SELECT c.relPath AS relPath, " +
            "(SELECT COUNT(*) FROM index_entries s WHERE s.parentRel = c.relPath AND s.kind = 0) AS folders, " +
            "(SELECT COUNT(*) FROM index_entries p WHERE p.parentRel = c.relPath AND p.kind = 1) AS photos " +
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
 * One container with its DIRECT sub-counts (immediate child folders and
 * immediate child photos) — the "n containers · m photos" line of a row.
 */
data class ChildCounts(
    val relPath: String,
    val folders: Int,
    val photos: Int,
)
