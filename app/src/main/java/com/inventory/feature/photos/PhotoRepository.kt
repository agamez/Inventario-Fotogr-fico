package com.inventory.feature.photos

import com.inventory.database.BoxDao
import com.inventory.database.BoxEntity
import com.inventory.database.PhotoDao
import com.inventory.database.PhotoEntity
import com.inventory.image.ImageUtils
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Single source of truth for photo mutations and observations.
 *
 * All read functions return cold [Flow]s and are flow-safe: callers decide
 * the dispatcher (the ViewModel hops to IO). [addPhoto] additionally owns
 * its IO hop internally because image normalization is CPU-bound work that
 * must never run on the caller's thread.
 */
@Singleton
class PhotoRepository @Inject constructor(
    private val photoDao: PhotoDao,
    private val boxDao: BoxDao,
) {

    /** Live parent box of the photo grid; feeds the screen title. */
    fun observeBox(boxId: Long): Flow<BoxEntity?> = boxDao.observeBox(boxId)

    /**
     * Live photos of [boxId] whose description matches [query] (empty
     * [query] returns everything), newest first.
     */
    fun observePhotos(boxId: Long, query: String): Flow<List<PhotoEntity>> =
        photoDao.observePhotosForBox(boxId, query)

    /** Live list of every box; feeds the move-to-box picker. */
    fun observeAllBoxes(): Flow<List<BoxEntity>> = boxDao.observeAllBoxes()

    /**
     * Normalizes [imageBytes] and inserts it into [boxId] after
     * [rawDescription] is trimmed.
     *
     * A blank description or an empty payload is refused up front; an
     * undecodable payload surfaces as [IllegalArgumentException] from
     * [ImageUtils.normalizeToJpegBlob] and maps to [AddOutcome.InvalidImage].
     */
    suspend fun addPhoto(
        boxId: Long,
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
                photoDao.insert(
                    PhotoEntity(
                        boxId = boxId,
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
        val current = photoDao.observePhoto(id).first()
            ?: throw IllegalStateException("Photo $id vanished before update.")
        photoDao.update(current.copy(description = description, note = note))
        return UpdateOutcome.Success
    }

    /** Deletes the photo [id]. */
    suspend fun deletePhoto(id: Long) {
        photoDao.deleteById(id)
    }

    /**
     * Re-parents every photo in [photoIds] onto [targetBoxId] in a single
     * atomic statement (no [PhotoEntity] ever dangles between boxes).
     */
    suspend fun movePhotos(photoIds: List<Long>, targetBoxId: Long) {
        if (photoIds.isEmpty()) return
        photoDao.movePhotos(photoIds, targetBoxId)
    }

    /** Result of an add attempt. */
    sealed interface AddOutcome {
        data object Success : AddOutcome
        data object BlankDescription : AddOutcome
        data object InvalidImage : AddOutcome
    }

    /** Result of a details update attempt. */
    sealed interface UpdateOutcome {
        data object Success : UpdateOutcome
        data object BlankDescription : UpdateOutcome
    }
}
