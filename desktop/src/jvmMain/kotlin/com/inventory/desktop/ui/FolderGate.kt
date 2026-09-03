package com.inventory.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.inventory.desktop.DesktopConfig
import kotlinx.coroutines.launch

/**
 * The pre-inventory gate: shown while no usable root folder is configured
 * (first launch, or a saved folder that no longer exists). "Choose
 * folder…" opens the native directory picker; picking persists the choice
 * to [DesktopConfig] and hands it to [onPicked], which builds the
 * controller and swaps the UI to the browser.
 */
@Composable
fun FolderGate(onPicked: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = { /* the gate may only be left by picking or closing the window */ }) {
        Column(
            modifier = Modifier
                .width(420.dp)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(DesktopStrings.PICK_FOLDER_TITLE, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text(
                DesktopStrings.PICK_FOLDER_MESSAGE,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(
                enabled = !busy,
                onClick = {
                    scope.launch {
                        busy = true
                        val path = pickFolder(DesktopStrings.FOLDER_PICKER_TITLE)
                        busy = false
                        if (path != null) onPicked(path)
                    }
                },
            ) {
                Text(DesktopStrings.PICK_FOLDER_BUTTON)
            }
        }
    }
}
