package com.inventory

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.inventory.feature.navigation.InventoryNavGraph
import com.inventory.share.ShareReceiver
import com.inventory.share.SharedImage
import com.inventory.storage.ExifEditor
import com.inventory.storage.ImageTranscoder
import com.inventory.ui.theme.PhotoInventoryTheme
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var transcoder: ImageTranscoder

    @Inject lateinit var exifEditor: ExifEditor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A rotation/recreation replays getIntent(); only the fresh launch
        // of a share should offer payloads to the picker.
        if (savedInstanceState == null) handleShareIntent(intent)
        setContent {
            PhotoInventoryTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    InventoryNavGraph()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Picks up an `ACTION_SEND` / `ACTION_SEND_MULTIPLE` image share: reads
     * every stream off the main thread, transcodes non-JPEG payloads and
     * parks them in [ShareReceiver] for the root browse screen's "Add to…"
     * picker. A share that carried no readable image offers an empty list so
     * the picker can report "No images shared".
     */
    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return
        val uris = if (action == Intent.ACTION_SEND) {
            listOfNotNull(intent.singleStream())
        } else {
            intent.multipleStreams()
        }
        if (uris.isEmpty()) {
            ShareReceiver.offer(emptyList())
            return
        }
        lifecycleScope.launch {
            val images = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri -> readSharedImage(uri) }
            }
            ShareReceiver.offer(images)
        }
    }

    /**
     * Reads one shared stream, keeps its EXIF description (when present) and
     * transcodes it to JPEG when needed. Null for an unreadable or
     * non-image payload so the rest of the share still lands.
     */
    private fun readSharedImage(uri: Uri): SharedImage? {
        val raw = readBytes(uri) ?: return null
        val description = runCatching { exifEditor.read(raw)?.description }.getOrNull()
        val jpeg = runCatching { transcoder.toJpeg(raw) }.getOrNull() ?: return null
        return SharedImage(
            bytes = jpeg,
            displayName = displayName(uri),
            exifDescription = description,
        )
    }

    private fun readBytes(uri: Uri): ByteArray? = runCatching {
        if (uri.scheme == "file") {
            uri.path?.let { File(it).readBytes() }
        } else {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /** The provider's DISPLAY_NAME, falling back to the URI's last segment. */
    private fun displayName(uri: Uri): String {
        val queried = if (uri.scheme != "file") {
            runCatching {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (index >= 0) cursor.getString(index) else null
                        } else {
                            null
                        }
                    }
            }.getOrNull()
        } else {
            null
        }
        return queried?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "shared"
    }

    @Suppress("DEPRECATION")
    private fun Intent.singleStream(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            getParcelableExtra(Intent.EXTRA_STREAM)
        }

    @Suppress("DEPRECATION")
    private fun Intent.multipleStreams(): List<Uri> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
}
