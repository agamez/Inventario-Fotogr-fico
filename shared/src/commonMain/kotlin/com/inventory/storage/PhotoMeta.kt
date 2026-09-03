package com.inventory.storage

/**
 * The metadata triple this app reads from and writes to a photo file:
 * the description, the note and the taken date (epoch millis, null when
 * absent). Pure data — the platform EXIF code that fills it lives behind
 * the [ExifEditor] seam.
 */
data class PhotoMeta(
    val description: String?,
    val note: String?,
    val dateTaken: Long?,
)
