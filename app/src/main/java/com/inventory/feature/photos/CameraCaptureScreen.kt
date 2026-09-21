package com.inventory.feature.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.inventory.R
import com.inventory.ai.VisionOutcome
import com.inventory.ui.rememberPreviewBitmap
import kotlinx.coroutines.launch

/**
 * CameraX capture screen for one container folder (nav argument `path` =
 * RELATIVE folder path): the reusable [CameraCaptureCore] provides the
 * permission gate, preview and shutter, and every successful capture is
 * kept as pending bytes in memory and opens the mandatory metadata sheet
 * shown here. Saving routes the bytes into the folder byte-identically
 * and navigates back via [CameraViewModel.CameraEvent.NavigateBack].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraCaptureScreen(
    onBack: () -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val suggestion by viewModel.suggestion.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CameraViewModel.CameraEvent.ShowMessage ->
                    snackbarHostState.showSnackbar(context.getString(event.resId))
                CameraViewModel.CameraEvent.NavigateBack -> onBack()
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.take_photo)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.navigate_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            CameraCaptureCore(
                modifier = Modifier.fillMaxSize(),
                onImageCaptured = viewModel::onCaptured,
                onCameraError = {
                    scope.launch {
                        snackbarHostState.showSnackbar(context.getString(R.string.error_camera))
                    }
                },
            )
        }
    }

    pending?.let { staged ->
        key(staged) {
            CaptureSaveDialog(
                imageBytes = staged,
                aiEnabled = aiEnabled,
                suggestion = suggestion,
                onSave = { description, note -> viewModel.save(description, note) },
                onDiscard = viewModel::discardPending,
            )
        }
    }
}

/**
 * Mandatory-metadata dialog for the just-captured frame (staged bytes):
 * preview, required description, optional note. Save stores the photo
 * (the ViewModel then asks the screen to navigate back);
 * Cancel/discard returns to the preview.
 */
@Composable
private fun CaptureSaveDialog(
    imageBytes: ByteArray,
    aiEnabled: Boolean,
    suggestion: VisionOutcome?,
    onSave: (String, String?) -> Unit,
    onDiscard: () -> Unit,
) {
    var description by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }

    // Blank-guarded pre-fill: a late AI reply never overwrites typed text.
    LaunchedEffect(suggestion) {
        val ready = suggestion as? VisionOutcome.Suggested ?: return@LaunchedEffect
        if (description.isBlank()) description = ready.description
        val suggestedNote = ready.note
        if (note.isBlank() && !suggestedNote.isNullOrBlank()) note = suggestedNote
    }

    AlertDialog(
        onDismissRequest = onDiscard,
        title = { Text(stringResource(R.string.save_photo)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = rememberPreviewBitmap(imageBytes),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.photo_description_required_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(stringResource(R.string.photo_note_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (aiEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    val status = when (suggestion) {
                        null -> R.string.ai_suggestion_thinking
                        is VisionOutcome.Suggested -> R.string.ai_suggestion_ready
                        is VisionOutcome.Failed -> R.string.ai_suggestion_failed
                    }
                    Text(
                        text = stringResource(status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(description, note.ifBlank { null }) },
                enabled = description.isNotBlank(),
            ) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}
