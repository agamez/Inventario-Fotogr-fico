package com.inventory.feature.containers

import com.inventory.database.ContainerDao
import com.inventory.database.ContainerEntity
import com.inventory.database.ContainerPath
import com.inventory.database.ContainerWithCounts
import com.inventory.database.PhotoEntity
import com.inventory.database.PhotoSearchResult
import com.inventory.image.ImageUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Single source of truth for the unified container tree and the photos
 * hanging on it. Container rules (per-sibling case-insensitive name
 * uniqueness, cycle-free moves, cascading deletes) and photo rules
 * (normalization, mandatory descriptions) all live here; the DAO below is
 * the only writer.
 *
 * All read functions return cold [Flow]s and all mutation functions are
 * suspend and flow-safe: callers decide the dispatcher (the ViewModel hops
 * to IO). [addPhoto] and [setCoverImage] own their IO hop because image
 * normalization is CPU-bound and must never run on the caller's thread.
 */
@Singleton
class ContainerRepository @Inject constructor(
    private val dao: ContainerDao,
) {

    /** Direct children of [parentId] (null = root) with their counts. */
    fun observeChildren(parentId: Long?): Flow<List<ContainerWithCounts>> =
        dao.observeChildren(parentId)

    /** Live container row [id]; feeds the screen title and options menu. */
    fun observeContainer(id: Long): Flow<ContainerEntity?> = dao.observeContainer(id)

    /** Root→leaf name chain of container [id] (leaf last). */
    fun observePath(id: Long): Flow<List<String>> =
        dao.observePathNames(id).map { leafFirst -> leafFirst.reversed() }

    /** Every container as a flat "Root / … / Leaf" row — picker data. */
    fun observeAllPaths(): Flow<List<ContainerPath>> = dao.observeAllPaths()

    /**
     * Creates a container under [parentId] (null = top level) after
     * [rawName] is trimmed.
     *
     * Blank names are rejected up front; duplicates are caught with a
     * case-insensitive per-sibling pre-check. Uniqueness is intentionally
     * repository-only (no DB index), which also keeps the data migration
     * collision-proof.
     */
    suspend fun createContainer(parentId: Long?, rawName: String): MutationOutcome {
        val name = rawName.trim()
        if (name.isEmpty()) return MutationOutcome.BlankName
        if (dao.countNamesLike(name, parentId, EXISTING_ID_NONE) > 0) {
            return MutationOutcome.DuplicateName
        }
        dao.insert(ContainerEntity(name = name, parentId = parentId))
        return MutationOutcome.Success
    }

    /**
     * Renames container [id] after [rawName] is trimmed, keeping its parent,
     * cover and creation timestamp. Same blank/duplicate rules as
     * [createContainer], scoped to the container's own sibling group; the
     * duplicate check ignores the row being renamed.
     */
    suspend fun renameContainer(id: Long, rawName: String): MutationOutcome {
        val name = rawName.trim()
        if (name.isEmpty()) return MutationOutcome.BlankName
        val current = dao.getById(id)
            ?: // Row vanished mid-flight; there is nothing left to rename.
            return MutationOutcome.Success
        if (dao.countNamesLike(name, current.parentId, id) > 0) {
            return MutationOutcome.DuplicateName
        }
        dao.update(current.copy(name = name))
        return MutationOutcome.Success
    }

    /**
     * Deletes container [id]; the self-referencing FK cascades the whole
     * subtree and every photo in it. The confirm dialog that guards this
     * action is built from [subtreeCounts].
     */
    suspend fun deleteContainer(id: Long) {
        dao.deleteById(id)
    }

    /**
     * Sizes of the subtree below [id] for the delete confirmation:
     * (child containers excluding [id] itself, photos including those in
     * [id] directly).
     */
    suspend fun subtreeCounts(id: Long): Pair<Int, Int> =
        dao.countSubtreeContainers(id) to dao.countSubtreePhotos(id)

    /**
     * Ids a move of [containerId] may not target: the container itself and
     * everything below it (pass 0 for the virtual root → nothing excluded;
     * the root cannot move).
     */
    suspend fun excludedIdsForMoves(containerId: Long): Set<Long> {
        if (containerId == ROOT_NODE_ID) return emptySet()
        return dao.subtreeIds(containerId).toSet()
    }

    /**
     * Re-parents container [id] onto [newParentId] (null = top level).
     * Refuses to move a container into itself or into any of its own
     * descendants, and onto a vanished target; otherwise the update is a
     * single atomic statement.
     */
    suspend fun moveContainer(id: Long, newParentId: Long?): MoveOutcome {
        if (newParentId != null) {
            if (dao.countInSubtree(id, newParentId) > 0) return MoveOutcome.InvalidTarget
            if (dao.getById(newParentId) == null) return MoveOutcome.InvalidTarget
        }
        dao.moveTo(id, newParentId)
        return MoveOutcome.Success
    }

    /**
     * Photos of container [nodeId] whose description matches [query]
     * (empty [query] returns everything), newest first.
     */
    fun observePhotosForContainer(nodeId: Long, query: String): Flow<List<PhotoEntity>> =
        dao.observePhotosForContainer(nodeId, query)

    /** Live photo row [id]; feeds the full-screen preview editor. */
    fun observePhoto(id: Long): Flow<PhotoEntity?> = dao.observePhoto(id)

    /**
     * Global search across descriptions and notes of every photo (blank
     * [query] browses everything), newest first, capped at 100 rows by the
     * DAO; results carry the container path and no image payload.
     */
    fun searchAll(query: String): Flow<List<PhotoSearchResult>> = dao.searchAll(query)

    /**
     * Stored JPEG blob of photo [photoId], or null when the row is gone.
     * Fetches the image column alone so search rows can show thumbnails
     * without loading whole entities.
     */
    suspend fun imageById(photoId: Long): ByteArray? = dao.imageById(photoId)

    /**
     * Normalizes [imageBytes] and inserts it into container [nodeId] after
     * [rawDescription] is trimmed.
     *
     * A blank description or an empty payload is refused up front; an
     * undecodable payload surfaces as [IllegalArgumentException] from
     * [ImageUtils.normalizeToJpegBlob] and maps to [AddOutcome.InvalidImage].
     */
    suspend fun addPhoto(
        nodeId: Long,
        imageBytes: ByteArray,
        rawDescription: String,
        note: String?,
    ): AddOutcome {
        val description = rawDescription.trim()
        if (description.isEmpty()) return AddOutcome.BlankDescription
        if (imageBytes.isEmpty()) return AddOutcome.InvalidImage
        return try {
            withContext(Dispatchers.IO) {
                val blob = ImageUtils.normalizeToJpegBlob(imageBytes)
                dao.insert(
                    PhotoEntity(
                        nodeId = nodeId,
                        image = blob,
                        description = description,
                        note = note?.trim()?.ifEmpty { null },
                    ),
                )
            }
            AddOutcome.Success
        } catch (exception: IllegalArgumentException) {
            AddOutcome.InvalidImage
        }
    }

    /**
     * Replaces the description/note of photo [id] after trimming. A blank
     * description is refused; the row is fetched first so the timestamp and
     * image are preserved. A vanished row is a programming error, never a
     * silent success.
     */
    suspend fun updatePhotoDetails(
        id: Long,
        rawDescription: String,
        rawNote: String?,
    ): UpdateOutcome {
        val description = rawDescription.trim()
        if (description.isEmpty()) return UpdateOutcome.BlankDescription
        val note = rawNote?.trim()?.ifEmpty { null }
        val current = withContext(Dispatchers.IO) { dao.observePhoto(id).first() }
            ?: throw IllegalStateException("Photo $id vanished before update.")
        dao.update(current.copy(description = description, note = note))
        return UpdateOutcome.Success
    }

    /** Deletes the photo [id]. */
    suspend fun deletePhoto(id: Long) {
        dao.deletePhotoById(id)
    }

    /**
     * Re-parents every photo in [photoIds] onto [targetContainerId] in a
     * single atomic statement (no [PhotoEntity] ever dangles).
     */
    suspend fun movePhotos(photoIds: List<Long>, targetContainerId: Long) {
        if (photoIds.isEmpty()) return
        dao.movePhotos(photoIds, targetContainerId)
    }

    /**
     * Sets container [id]'s single cover photo from raw [bytes], normalized
     * to a thumbnail-sized JPEG blob; null [bytes] clears the cover (the
     * ViewModel passes null for "Remove photo"). An undecodable image
     * surfaces as [IllegalArgumentException] for the caller to catch.
     */
    suspend fun setCoverImage(id: Long, bytes: ByteArray?) {
        if (bytes == null) {
            withContext(Dispatchers.IO) { dao.setCoverImage(id, null) }
            return
        }
        val blob = withContext(Dispatchers.IO) {
            ImageUtils.normalizeToJpegBlob(bytes, ImageUtils.COVER_MAX_DIMENSION_PX)
        }
        withContext(Dispatchers.IO) { dao.setCoverImage(id, blob) }
    }

    /** Result of a container create/rename attempt. */
    sealed interface MutationOutcome {
        data object Success : MutationOutcome
        data object BlankName : MutationOutcome
        data object DuplicateName : MutationOutcome
    }

    /** Result of a container move attempt. */
    sealed interface MoveOutcome {
        data object Success : MoveOutcome
        data object InvalidTarget : MoveOutcome
    }

    /** Result of a photo add attempt. */
    sealed interface AddOutcome {
        data object Success : AddOutcome
        data object BlankDescription : AddOutcome
        data object InvalidImage : AddOutcome
    }

    /** Result of a photo details update attempt. */
    sealed interface UpdateOutcome {
        data object Success : UpdateOutcome
        data object BlankDescription : UpdateOutcome
    }

    private companion object {
        /** Sentinel for "no row to exclude" in the duplicate-name check. */
        const val EXISTING_ID_NONE = 0L

        /** Virtual root of the tree; never a real row, never moves. */
        const val ROOT_NODE_ID = 0L
    }
}
