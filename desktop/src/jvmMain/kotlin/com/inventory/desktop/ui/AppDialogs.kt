package com.inventory.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.inventory.ai.VisionOutcome
import com.inventory.desktop.DesktopAppController
import com.inventory.desktop.index.DesktopIndex
import com.inventory.desktop.mergedPreFill
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The desktop dialogs: the same modal surfaces the Android screen builds
 * (name entry, folder picker, confirmations, the photo metadata editor
 * and the import queue sheet), re-expressed as Compose `Dialog`s. All
 * user-facing text comes from [DesktopStrings].
 */

/** Single-field name dialog for creating and renaming containers. */
@Composable
fun NameDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var value by remember(title, initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(DesktopStrings.NAME_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = value.isNotBlank(),
                onClick = { onConfirm(value) },
            ) { Text(DesktopStrings.SAVE) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(DesktopStrings.CANCEL) }
        },
    )
}

/** A scrollable list of folder targets for the move dialogs. */
@Composable
fun FolderPickerDialog(
    title: String,
    choices: List<DesktopAppController.FolderChoice>,
    onDismiss: () -> Unit,
    onPick: (DesktopAppController.FolderChoice) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (choices.isEmpty()) {
                    Text(
                        DesktopStrings.NO_FOLDER_TARGETS,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                choices.forEach { choice ->
                    Text(
                        text = choice.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(choice) }
                            .padding(vertical = 10.dp),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(DesktopStrings.CANCEL) }
        },
    )
}

/** Yes/no confirmation used before deleting anything from disk. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                content = { Text(confirmLabel, color = MaterialTheme.colorScheme.error) },
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(DesktopStrings.CANCEL) }
        },
    )
}

/**
 * The photo editor: preview, the EXIF description/note (description is
 * the file's name on disk — saving a changed one renames via the
 * controller), plus delete / move-to / close, mirroring the Android
 * full-screen dialog's actions.
 */
@Composable
fun PhotoDetailsDialog(
    controller: DesktopAppController,
    photo: DesktopIndex.IndexedPhoto,
    onDismiss: () -> Unit,
    onOutcome: (DesktopAppController.Outcome) -> Unit,
    onDeleted: () -> Unit,
    onMoveRequested: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var description by remember(photo.relPath, photo.description) { mutableStateOf(photo.description) }
    var note by remember(photo.relPath, photo.note) { mutableStateOf(photo.note ?: "") }
    var confirmDelete by remember(photo.relPath) { mutableStateOf(false) }
    val file = photoFileOf(controller, photo.relPath)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .padding(16.dp),
        ) {
            Text(DesktopStrings.PHOTO_DETAILS_TITLE, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            ImageTile(file = file, size = 260.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(12.dp))
            Text(
                text = DesktopStrings.PHOTO_PATH_LINE.format(controller.labelOf(photo.parentRel)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            photo.dateTaken?.let { millis ->
                Text(
                    text = "${DesktopStrings.DATE_TAKEN_LABEL}: " +
                        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } ?: run {
                Text(
                    "${DesktopStrings.DATE_TAKEN_LABEL}: ${DesktopStrings.NO_DATE}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text(DesktopStrings.DESCRIPTION_LABEL) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text(DesktopStrings.NOTE_LABEL) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(DesktopStrings.DELETE, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onMoveRequested) { Text(DesktopStrings.MOVE_TO) }
                Spacer(Modifier.width(8.dp))
                Button(
                    enabled = description.isNotBlank(),
                    onClick = {
                        scope.launch {
                            val outcome = controller.updatePhotoMetadata(
                                photo.relPath,
                                description,
                                note.ifBlank { null },
                            )
                            onOutcome(outcome)
                            onDismiss()
                        }
                    },
                ) { Text(DesktopStrings.SAVE) }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text(DesktopStrings.CLOSE) }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = DesktopStrings.DELETE_PHOTO_TITLE,
            message = DesktopStrings.DELETE_PHOTO_MESSAGE.format(photo.description),
            confirmLabel = DesktopStrings.DELETE,
            onDismiss = { confirmDelete = false },
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    controller.deletePhoto(photo.relPath)
                    onDeleted()
                }
            },
        )
    }
}

/**
 * One photo of an import batch: preview of the staged bytes, the
 * mandatory description, the optional note, how many are still waiting,
 * and — when AI auto-labeling is on — a status line with a blank-guarded
 * pre-fill from the suggestion. Cancel discards the whole queue (Android
 * semantics).
 */
@Composable
fun ImportQueueDialog(
    photo: DesktopAppController.PendingPhoto,
    remaining: Int,
    aiEnabled: Boolean,
    suggestion: VisionOutcome?,
    onSave: (String, String?) -> Unit,
    onCancel: () -> Unit,
) {
    var description by remember(photo.id) { mutableStateOf("") }
    var note by remember(photo.id) { mutableStateOf("") }
    val bitmap = remember(photo.bytes) { decodeJpegBitmap(photo.bytes) }

    // Blank-guarded pre-fill: a late AI reply never overwrites text the
    // user already typed into either field (the rule lives in mergedPreFill).
    LaunchedEffect(photo.id, suggestion) {
        val merged = mergedPreFill(description, note, suggestion)
        description = merged.description
        note = merged.note
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(DesktopStrings.IMPORT_TITLE) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        modifier = Modifier.size(220.dp),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (remaining > 0) {
                    Text(
                        DesktopStrings.N_MORE_WAITING.format(remaining),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(DesktopStrings.DESCRIPTION_LABEL) },
                    singleLine = true,
                    supportingText = { Text(DesktopStrings.DESCRIPTION_REQUIRED) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text(DesktopStrings.NOTE_LABEL) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (aiEnabled) {
                    Spacer(Modifier.height(8.dp))
                    AiSuggestionStatus(suggestion)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = description.isNotBlank(),
                onClick = { onSave(description, note) },
            ) { Text(if (remaining > 0) DesktopStrings.NEXT else DesktopStrings.SAVE) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(DesktopStrings.CANCEL) }
        },
    )
}

/**
 * One-line AI status under the import fields: in-flight while no reply has
 * arrived, ready on a suggestion, failed otherwise (Android wording).
 */
@Composable
private fun AiSuggestionStatus(suggestion: VisionOutcome?) {
    val label = when (suggestion) {
        null -> DesktopStrings.AI_SUGGESTION_THINKING
        is VisionOutcome.Suggested -> DesktopStrings.AI_SUGGESTION_READY
        is VisionOutcome.Failed -> DesktopStrings.AI_SUGGESTION_FAILED
    }
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The AI auto-label settings: master switch, endpoint/key/model fields
 * (disabled while the switch is off) and a "Test connection" probe that
 * shows its result inline. Edits persist immediately, like Android.
 */
@Composable
fun SettingsDialog(
    controller: DesktopAppController,
    onDismiss: () -> Unit,
) {
    val settings by controller.aiSettings.collectAsState()
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DesktopStrings.SETTINGS_TITLE) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(DesktopStrings.AI_ENABLE, style = MaterialTheme.typography.titleMedium)
                        Text(
                            DesktopStrings.AI_ENABLE_HELP,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = { controller.setAiEnabled(it) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = settings.baseUrl,
                    onValueChange = { controller.setAiBaseUrl(it) },
                    enabled = settings.enabled,
                    label = { Text(DesktopStrings.AI_BASE_URL) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.apiKey,
                    onValueChange = { controller.setAiApiKey(it) },
                    enabled = settings.enabled,
                    label = { Text(DesktopStrings.AI_API_KEY) },
                    supportingText = { Text(DesktopStrings.AI_KEY_HINT) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = settings.model,
                    onValueChange = { controller.setAiModel(it) },
                    enabled = settings.enabled,
                    label = { Text(DesktopStrings.AI_MODEL) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        enabled = settings.enabled && !testing,
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                testResult = when (val outcome = controller.testConnection()) {
                                    is VisionOutcome.Suggested -> DesktopStrings.AI_TEST_OK
                                    is VisionOutcome.Failed ->
                                        DesktopStrings.AI_TEST_FAILED +
                                            outcome.reason.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                                }
                                testing = false
                            }
                        },
                    ) { Text(DesktopStrings.AI_TEST_CONNECTION) }
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                testResult?.let { result ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = result,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(DesktopStrings.CLOSE) }
        },
    )
}

/** The photo file behind a dialog preview, refreshed with the index. */
@Composable
private fun photoFileOf(controller: DesktopAppController, rel: String): java.io.File? {
    var file by remember(rel) { mutableStateOf<java.io.File?>(null) }
    LaunchedEffect(rel) { file = controller.photoFile(rel) }
    return file
}
