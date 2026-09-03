package com.inventory.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * File-thumbnail plumbing: the desktop backend hands the UI plain
 * [File] models (the same object the Android app feeds Coil), so images
 * are decoded straight from disk through Skia — no HTTP, no cache;
 * reloading is keyed on the path + the file's own timestamp.
 */

/** Decodes a JPEG payload into a Compose [ImageBitmap]; null for unparsable bytes. */
fun decodeJpegBitmap(bytes: ByteArray): ImageBitmap? =
    runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

/** The decoded [file] (off the UI thread), or null while loading / when unreadable. */
@Composable
fun rememberFileBitmap(file: File?): ImageBitmap? {
    val path = file?.absolutePath
    val stamp = file?.length() ?: -1L
    var bitmap by remember(path, stamp) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path, stamp) {
        val source = file ?: return@LaunchedEffect
        bitmap = withContext(Dispatchers.IO) {
            runCatching { source.readBytes() }.getOrNull()?.let { decodeJpegBitmap(it) }
        }
    }
    return bitmap
}

/** A rounded square image tile; a tinted placeholder stands in while decoding. */
@Composable
fun ImageTile(
    file: File?,
    size: Dp? = null,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val bitmap = rememberFileBitmap(file)
    val sized = if (size != null) modifier.size(size) else modifier
    Box(
        modifier = sized.clip(RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
    }
}
