package com.inventory.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * The single DAO of the hierarchical container model: children + counts,
 * ancestor/descendant walks (recursive CTEs), per-sibling duplicate-name
 * checks, moves with cycle detection, and every photo query the browse and
 * search screens need. Flow reads are live; writes are suspend.
 */
@Dao
interface ContainerDao {

    /**
     * Direct children of [parentId] with child/photo counts, alphabetized
     * case-insensitively. Pass null for the virtual root (the `IS :parentId`
     * comparison matches NULL only when the binding is NULL).
     */
    @Query(
        "SELECT c.*, " +
            "(SELECT COUNT(*) FROM containers s WHERE s.parentId = c.id) AS childCount, " +
            "(SELECT COUNT(*) FROM photos p WHERE p.nodeId = c.id) AS photoCount " +
            "FROM containers c WHERE c.parentId IS :parentId " +
            "ORDER BY c.name COLLATE NOCASE ASC",
    )
    fun observeChildren(parentId: Long?): Flow<List<ContainerWithCounts>>

    @Query("SELECT * FROM containers WHERE id = :id")
    fun observeContainer(id: Long): Flow<ContainerEntity?>

    @Query("SELECT * FROM containers WHERE id = :id")
    suspend fun getById(id: Long): ContainerEntity?

    /**
     * Names from [id] up to (and including) its root, leaf first; the
     * repository reverses this into a root→leaf breadcrumb. The 64-row
     * limit bounds the walk against accidental cycles in stored data.
     */
    @Query(
        "WITH RECURSIVE chain(id, name, parent) AS (" +
            "SELECT id, name, parentId FROM containers WHERE id = :id " +
            "UNION ALL " +
            "SELECT c.id, c.name, c.parentId FROM containers c JOIN chain ON chain.parent = c.id " +
            "LIMIT 64" +
            ") SELECT name FROM chain",
    )
    fun observePathNames(id: Long): Flow<List<String>>

    /** Per-sibling duplicate-name check; pass [excludeId] = 0 when inserting. */
    @Query(
        "SELECT COUNT(*) FROM containers " +
            "WHERE name = :name COLLATE NOCASE AND parentId IS :parentId AND id != :excludeId",
    )
    suspend fun countNamesLike(name: String, parentId: Long?, excludeId: Long): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(c: ContainerEntity): Long

    @Update
    suspend fun update(c: ContainerEntity)

    /** Removes the container; SQLite cascades the whole subtree + their photos. */
    @Query("DELETE FROM containers WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE containers SET parentId = :newParentId WHERE id = :id")
    suspend fun moveTo(id: Long, newParentId: Long?)

    /**
     * 1 when [targetId] is [rootId] itself or lives inside its subtree —
     * the app-level cycle guard for moves (0 otherwise). Depth-bounded.
     */
    @Query(
        "WITH RECURSIVE sub(id, depth) AS (" +
            "SELECT :rootId, 0 " +
            "UNION ALL " +
            "SELECT c.id, s.depth + 1 FROM containers c JOIN sub s ON c.parentId = s.id " +
            "WHERE s.depth < 64" +
            ") SELECT COUNT(*) FROM sub WHERE id = :targetId",
    )
    suspend fun countInSubtree(rootId: Long, targetId: Long): Int

    /** Ids of [rootId] and every container below it (depth-bounded walk). */
    @Query(
        "WITH RECURSIVE sub(id, depth) AS (" +
            "SELECT :rootId, 0 " +
            "UNION ALL " +
            "SELECT c.id, s.depth + 1 FROM containers c JOIN sub s ON c.parentId = s.id " +
            "WHERE s.depth < 64" +
            ") SELECT id FROM sub",
    )
    suspend fun subtreeIds(rootId: Long): List<Long>

    /** Containers in [rootId]'s subtree, the root itself excluded. */
    @Query(
        "WITH RECURSIVE sub(id, depth) AS (" +
            "SELECT :rootId, 0 " +
            "UNION ALL " +
            "SELECT c.id, s.depth + 1 FROM containers c JOIN sub s ON c.parentId = s.id " +
            "WHERE s.depth < 64" +
            ") SELECT COUNT(*) - 1 FROM sub",
    )
    suspend fun countSubtreeContainers(rootId: Long): Int

    /** Photos attached to [rootId] or to any container below it. */
    @Query(
        "WITH RECURSIVE sub(id, depth) AS (" +
            "SELECT :rootId, 0 " +
            "UNION ALL " +
            "SELECT c.id, s.depth + 1 FROM containers c JOIN sub s ON c.parentId = s.id " +
            "WHERE s.depth < 64" +
            ") SELECT COUNT(*) FROM photos WHERE nodeId IN (SELECT id FROM sub)",
    )
    suspend fun countSubtreePhotos(rootId: Long): Int

    /**
     * Every container as a flat "Root / … / Leaf" path, path-sorted — the
     * data behind the move and photo-move pickers. Depth-bounded at 512
     * rows; a cycle in stored data could never terminate otherwise.
     */
    @Query(
        "WITH RECURSIVE tree(id, parent, path) AS (" +
            "SELECT id, parentId, name FROM containers WHERE parentId IS NULL " +
            "UNION ALL " +
            "SELECT c.id, c.parentId, t.path || ' / ' || c.name FROM containers c " +
            "JOIN tree t ON c.parentId = t.id " +
            "LIMIT 512" +
            ") SELECT id, path FROM tree ORDER BY path COLLATE NOCASE ASC",
    )
    fun observeAllPaths(): Flow<List<ContainerPath>>

    /**
     * Photos of container [nodeId] whose description matches [query]
     * (empty [query] returns everything), newest first. SQLite LIKE is
     * case-insensitive for ASCII — acceptable for search.
     */
    @Query(
        "SELECT * FROM photos WHERE nodeId = :nodeId AND " +
            "(:query = '' OR description LIKE '%' || :query || '%') " +
            "ORDER BY createdAt DESC",
    )
    fun observePhotosForContainer(nodeId: Long, query: String): Flow<List<PhotoEntity>>

    @Query("SELECT * FROM photos WHERE id = :id")
    fun observePhoto(id: Long): Flow<PhotoEntity?>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(photo: PhotoEntity): Long

    @Update
    suspend fun update(photo: PhotoEntity)

    @Query("DELETE FROM photos WHERE id = :id")
    suspend fun deletePhotoById(id: Long)

    /** Atomic move — photos never linger detached from a container. */
    @Query("UPDATE photos SET nodeId = :targetContainerId WHERE id IN (:photoIds)")
    suspend fun movePhotos(photoIds: List<Long>, targetContainerId: Long)

    @Query("SELECT COUNT(*) FROM photos WHERE nodeId = :nodeId")
    suspend fun countInContainer(nodeId: Long): Int

    /** The stored JPEG blob of photo [photoId], or null when the row is gone. */
    @Query("SELECT image FROM photos WHERE id = :photoId")
    suspend fun imageById(photoId: Long): ByteArray?

    /**
     * Global search across descriptions and notes of every photo (blank
     * [query] browses everything), newest first, capped at 100 rows. The
     * recursive CTE materializes each container's full ancestor path so a
     * result row can say exactly where the item lives; the LEFT JOIN keeps
     * detached photos visible with a null path. The projection omits the
     * image BLOB on purpose.
     */
    @Query(
        "WITH RECURSIVE tree(id, parent, path) AS (" +
            "SELECT id, parentId, name FROM containers WHERE parentId IS NULL " +
            "UNION ALL " +
            "SELECT c.id, c.parentId, t.path || ' / ' || c.name FROM containers c " +
            "JOIN tree t ON c.parentId = t.id " +
            "LIMIT 512" +
            ") " +
            "SELECT photos.id AS photoId, photos.nodeId AS nodeId, " +
            "photos.description AS description, photos.note AS note, " +
            "photos.createdAt AS createdAt, tree.path AS containerPath " +
            "FROM photos LEFT JOIN tree ON photos.nodeId = tree.id " +
            "WHERE :query = '' OR photos.description LIKE '%' || :query || '%' " +
            "OR photos.note LIKE '%' || :query || '%' " +
            "ORDER BY photos.createdAt DESC LIMIT 100",
    )
    fun searchAll(query: String): Flow<List<PhotoSearchResult>>

    /** Sets (or clears, when [bytes] is null) the container's single cover photo. */
    @Query("UPDATE containers SET coverImage = :bytes WHERE id = :id")
    suspend fun setCoverImage(id: Long, bytes: ByteArray?)
}
