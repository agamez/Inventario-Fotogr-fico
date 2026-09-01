package com.inventory.image

import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import java.security.MessageDigest

/**
 * Produces a stable cache key (SHA-256 hex digest) for JPEG [ByteArray]s so that
 * photos stored as Room BLOBs participate in Coil's memory cache.
 */
class ByteArrayKeyer : Keyer<ByteArray> {

    override fun key(data: ByteArray, options: Options): String? {
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        val hex = StringBuilder(digest.size * 2)
        for (byte in digest) {
            hex.append(String.format("%02x", byte))
        }
        return "byte-array-$hex"
    }
}

/**
 * Decodes JPEG [ByteArray] data (e.g. loaded from a Room BLOB column) into a
 * drawable that Coil can render via `AsyncImage` / `rememberImagePainter`.
 *
 * Uses BitmapFactory directly and returns a Coil [DrawableResult], which works
 * on every supported API level (minSdk 24) without relying on the platform
 * ImageDecoder.
 */
class ByteArrayFetcher(
    private val data: ByteArray,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size) ?: return null
        val drawable = BitmapDrawable(options.context.resources, bitmap)
        return DrawableResult(
            drawable = drawable,
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory : Fetcher.Factory<ByteArray> {
        override fun create(data: ByteArray, options: Options, imageLoader: ImageLoader): Fetcher =
            ByteArrayFetcher(data, options)
    }
}
