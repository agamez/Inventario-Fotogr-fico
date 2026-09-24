package com.inventory.storage

/**
 * Produces the small, self-contained preview bytes that the inventory
 * report embeds (as `data:image/jpeg;base64,…`) so the document renders
 * without any sibling files.
 *
 * This is a DERIVED artifact: the original photo bytes are decoded for
 * display only and are never written back or re-encoded, honouring the
 * project's disk-is-truth rule. The interface lives in `:shared` (no
 * Android imports); each platform injects its native codec
 * ([android.graphics.BitmapFactory] on Android, `javax.imageio` on the
 * desktop), mirroring [ImageTranscoder] and [ImageHasher].
 */
interface ImageThumbnailer {

    /**
     * Decodes [bytes], applies the EXIF orientation so the preview is
     * upright, downscales it so its longest side is at most [maxDim] and
     * encodes the result as a JPEG, or null when the payload cannot be
     * decoded/encoded. Callers fall back to a placeholder cell.
     */
    fun thumbnail(bytes: ByteArray, maxDim: Int): ByteArray?

    companion object {

        /** Longest side of a report thumbnail, in pixels. */
        const val THUMBNAIL_MAX_DIM = 256

        /** JPEG quality used for report thumbnails (visually lossless enough). */
        const val THUMBNAIL_QUALITY = 75
    }
}
