package com.inventory.feature.locations

import com.inventory.database.LocationDao
import com.inventory.database.LocationEntity
import com.inventory.database.LocationWithBoxes
import com.inventory.image.ImageUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Single source of truth for location mutations and observations.
 *
 * All functions are suspend or return cold [Flow]s and are flow-safe:
 * callers decide the dispatcher (the ViewModel hops to IO).
 */
@Singleton
class LocationRepository @Inject constructor(
    private val locationDao: LocationDao,
) {

    /** Live list of every location together with the boxes it contains. */
    fun observeLocations(): Flow<List<LocationWithBoxes>> =
        locationDao.observeLocationsWithBoxes()

    /**
     * Creates a location after [rawName] is trimmed.
     *
     * Blank names are rejected up front; duplicates are caught with a
     * case-insensitive pre-check and, in the tiny race window between check
     * and write, by the unique index (insert returns -1).
     */
    suspend fun createLocation(rawName: String): MutationOutcome {
        val name = rawName.trim()
        if (name.isEmpty()) return MutationOutcome.BlankName
        if (locationDao.countNamesLike(name, EXISTING_ID_NONE) > 0) {
            return MutationOutcome.DuplicateName
        }
        return if (locationDao.insert(LocationEntity(name = name)) == -1L) {
            MutationOutcome.DuplicateName
        } else {
            MutationOutcome.Success
        }
    }

    /**
     * Renames the location [id] after [rawName] is trimmed, keeping the
     * original creation timestamp. Same blank/duplicate rules as
     * [createLocation]; the duplicate check ignores the row being renamed.
     */
    suspend fun renameLocation(id: Long, rawName: String): MutationOutcome {
        val name = rawName.trim()
        if (name.isEmpty()) return MutationOutcome.BlankName
        if (locationDao.countNamesLike(name, id) > 0) {
            return MutationOutcome.DuplicateName
        }
        val current = locationDao.observeLocation(id).first()
            ?: // Row vanished mid-flight; there is nothing left to rename.
            return MutationOutcome.Success
        locationDao.update(current.copy(name = name))
        return MutationOutcome.Success
    }

    /**
     * Deletes the location [id]. The DB FK on boxes is RESTRICT, so the app
     * pre-checks and refuses while the location still contains boxes.
     */
    suspend fun deleteLocation(id: Long): DeleteOutcome {
        if (locationDao.boxCountIn(id) > 0) return DeleteOutcome.HasBoxes
        locationDao.deleteById(id)
        return DeleteOutcome.Success
    }

    /**
     * Sets the location's single cover photo from raw [bytes], normalized to
     * a thumbnail-sized JPEG blob; a null [bytes] clears the cover. Owns its
     * IO hop because normalization is CPU-bound and must never run on the
     * caller's thread.
     */
    suspend fun setCoverImage(id: Long, bytes: ByteArray?) {
        if (bytes == null) {
            withContext(Dispatchers.IO) { locationDao.setCoverImage(id, null) }
            return
        }
        val blob = withContext(Dispatchers.IO) {
            ImageUtils.normalizeToJpegBlob(bytes, ImageUtils.COVER_MAX_DIMENSION_PX)
        }
        withContext(Dispatchers.IO) { locationDao.setCoverImage(id, blob) }
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
        data object HasBoxes : DeleteOutcome
    }

    private companion object {
        /** Sentinel for "no row to exclude" in the duplicate-name check. */
        const val EXISTING_ID_NONE = 0L
    }
}
