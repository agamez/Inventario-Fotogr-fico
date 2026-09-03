package com.inventory.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compose glue for the dual-mode storage: images are no longer addressed
 * by absolute `File`s on screen, but by models the ACTIVE backend resolves
 * for a relative path (a `File` in File mode, a content uri in SAF mode) —
 * resolved off the main thread with remember+LaunchedEffect, exactly the
 * pattern the screens already used for one-shot work.
 */

/**
 * Resolves [loader] for [relPath] off the composition: null (nothing
 * drawn yet) until the backend model arrives; re-resolves whenever
 * [relPath] changes. The screen's AsyncImage can take the result as-is
 * (Coil handles [java.io.File], [android.net.Uri] and [Bitmap] models).
 */
@Composable
fun rememberBackendModel(relPath: String, loader: suspend (String) -> Any?): Any? {
    var model by remember(relPath) { mutableStateOf<Any?>(null) }
    LaunchedEffect(relPath) {
        model = loader(relPath)
    }
    return model
}

/**
 * Downsampled preview [Bitmap] of staged in-memory bytes (camera frames,
 * gallery picks — the app stages bytes, not files); decoded off the main
 * thread, never a full-size allocation. Null while decoding or on failure.
 */
@Composable
fun rememberPreviewBitmap(bytes: ByteArray?): Bitmap? {
    var bitmap by remember(bytes) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(bytes) {
        bitmap = bytes?.let { withContext(Dispatchers.Default) { decodePreview(it) } }
    }
    return bitmap
}

/** Largest edge kept for on-screen previews of staged bytes. */
private const val PREVIEW_MAX_DIM = 1080

private fun decodePreview(bytes: ByteArray): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    var sample = 1
    while (bounds.outWidth / sample > PREVIEW_MAX_DIM || bounds.outHeight / sample > PREVIEW_MAX_DIM) {
        sample *= 2
    }
    BitmapFactory.decodeByteArray(
        bytes,
        0,
        bytes.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
}.getOrNull()
