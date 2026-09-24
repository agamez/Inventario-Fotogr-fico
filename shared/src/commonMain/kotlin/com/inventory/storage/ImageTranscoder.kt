package com.inventory.storage

/**
 * Converts arbitrary image payloads into JPEG so they can enter the
 * filesystem inventory, whose one photo format is JPEG.
 *
 * This is a FORMAT transcode, not a quality normalization: a payload that
 * already carries the JPEG SOI marker is returned UNCHANGED, so imported
 * JPEGs stay byte-identical (no re-encode, no generation loss), and only
 * other image formats (PNG, WebP, …) are decoded and re-encoded as JPEG.
 * Non-image payloads raise [IllegalArgumentException], which callers map
 * to the invalid-image message.
 *
 * The interface lives in `:shared` (no Android imports); each platform
 * injects its native codec ([android.graphics.BitmapFactory] on Android,
 * `javax.imageio` on the desktop).
 */
interface ImageTranscoder {

    /**
     * Returns [bytes] re-encoded as JPEG at [quality] (1..100) when they
     * are not already a JPEG, or [bytes] unchanged when they already are.
     * Throws [IllegalArgumentException] for a payload that is not a
     * decodable image or cannot be encoded.
     */
    fun toJpeg(bytes: ByteArray, quality: Int = DEFAULT_QUALITY): ByteArray

    companion object {

        /** 90 is visually lossless enough for a container snapshot. */
        const val DEFAULT_QUALITY = 90

        /**
         * True when [bytes] begins with the JPEG SOI marker (`FF D8 FF`) —
         * the same cheap 3-byte check every platform's `ExifEditor` uses.
         */
        fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size >= 3 &&
                bytes[0] == 0xFF.toByte() &&
                bytes[1] == 0xD8.toByte() &&
                bytes[2] == 0xFF.toByte()
    }
}
