package com.inventory.feature.boxes

import com.inventory.database.BoxDao
import com.inventory.database.BoxEntity
import com.inventory.database.BoxWithPhotoCount
import com.inventory.database.LocationDao
import com.inventory.database.LocationEntity
import com.inventory.image.ImageUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Single source of truth for box mutations and observations.
 *
 * All functions are suspend or return cold [Flow]s and are flow-safe:
 * callers decide the dispatcher (the ViewModel hops to IO).
 */
@Singleton
class BoxRepository @Inject constructor(
    private val boxDao: BoxDao,
    private val locationDao: LocationDao,
) {

    /** Live list of the boxes inside [locationId], each with its photo count. */
    fun observeBoxesForLocation(locationId: Long): Flow<List<BoxWithPhotoCount>> =
        boxDao.observeBoxesForLocation(locationId)

    /** Live parent location of the box list; feeds the screen title. */
    fun observeLocation(locationId: Long): Flow<LocationEntity?> =
        locationDao.observeLocation(locationId)

    /** Live list of every location; feeds the move-to-location picker. */
    fun observeLocations(): Flow<List<LocationEntity>> =
        locationDao.observeLocations()

    /**
     * Creates a box inside [locationId] after [rawName] is trimmed.
     *
     * Blank names are rejected up front; duplicates are caught with a
     * case-insensitive pre-check and, in the tiny race window between check
     * and write, by the unique index (insert returns -1). Uniqueness is
     * global across every location, matching the DB schema.
     */
    suspend fun createBox(locationId: Long, rawName: String): MutationOutcome {
        val name = rawName.trim()
        if (name.isEmpty()) return MutationOutcome.BlankName
        if (boxDao.countNamesLike(name, EXISTING_ID_NONE) > 0) {
            return MutationOutcome.DuplicateName
        }
        return if (boxDao.insert(BoxEntity(name = name, locationId = locationId)) == -1L) {
            MutationOutcome.DuplicateName
        } else {
            MutationOutcome.Success
        }
    }

    /**
     * Renames the box [id] after [rawName] is trimmed, keeping the original
     * location and creation timestamp. Same blank/duplicate rules as
     * [createBox]; the duplicate check ignores the row being renamed.
     */
    suspend fun renameBox(id: Long, rawName: String): MutationOutcome {
        val name = rawName.trim()
        if (name.isEmpty()) return MutationOutcome.BlankName
        if (boxDao.countNamesLike(name, id) > 0) {
            return MutationOutcome.DuplicateName
        }
        val current = boxDao.observeBox(id).first()
            ?: // Row vanished mid-flight; there is nothing left to rename.
            return MutationOutcome.Success
        boxDao.update(current.copy(name = name))
        return MutationOutcome.Success
    }

    /**
     * Deletes the box [id]. The photos inside it are removed by the FK
     * CASCADE on the [com.inventory.database.PhotoEntity] side, so the
     * operation always succeeds.
     */
    suspend fun deleteBox(id: Long): DeleteOutcome {
        boxDao.deleteById(id)
        return DeleteOutcome.Success
    }

    /**
     * Re-parents the box [boxId] onto [newLocationId]. Names stay globally
     * unique, so a move can never create a duplicate and cannot fail.
     */
    suspend fun moveBox(boxId: Long, newLocationId: Long) {
        boxDao.moveToLocation(boxId, newLocationId)
    }

    /**
     * Sets the box's single cover photo from raw [bytes], normalized to a
     * thumbnail-sized JPEG blob; a null [bytes] clears the cover. Owns its
     * IO hop because normalization is CPU-bound and must never run on the
     * caller's thread.
     */
    suspend fun setCoverImage(id: Long, bytes: ByteArray?) {
        if (bytes == null) {
            withContext(Dispatchers.IO) { boxDao.setCoverImage(id, null) }
            return
        }
        val blob = withContext(Dispatchers.IO) {
            ImageUtils.normalizeToJpegBlob(bytes, ImageUtils.COVER_MAX_DIMENSION_PX)
        }
        withContext(Dispatchers.IO) { boxDao.setCoverImage(id, blob) }
    }

    /** Result of a create/rename attempt. */
    sealed interface MutationOutcome {
        data object Success : MutationOutcome
        data object BlankName : MutationOutcome
        data object DuplicateName : MutationOutcome
    }

    /** Result of a delete attempt. */
    sealed interface DeleteOutcome {
        data object Success : DeleteOutcome
    }

    private companion object {
        /** Sentinel for "no row to exclude" in the duplicate-name check. */
        const val EXISTING_ID_NONE = 0L
    }
}
