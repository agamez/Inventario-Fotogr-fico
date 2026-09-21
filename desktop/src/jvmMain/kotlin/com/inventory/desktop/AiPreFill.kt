package com.inventory.desktop

import com.inventory.ai.VisionOutcome

/** The description/note pair an import dialog holds while the user edits it. */
data class PreFill(val description: String, val note: String)

/**
 * The blank-guarded pre-fill rule of the import dialog, extracted as a
 * pure function so it is testable without a composable: an AI
 * [suggestion] only fills fields the user has not typed into yet.
 *
 * A [VisionOutcome.Suggested] replaces a blank description and, when the
 * user left the note empty, a non-blank suggested note. Running it again
 * with already-filled fields (or a late/absent suggestion) is a no-op,
 * so a reply that lands after the user started typing never overwrites
 * their text.
 */
fun mergedPreFill(currentDescription: String, currentNote: String, suggestion: VisionOutcome?): PreFill {
    val ready = suggestion as? VisionOutcome.Suggested ?: return PreFill(currentDescription, currentNote)
    val suggestedNote = ready.note
    return PreFill(
        description = if (currentDescription.isBlank()) ready.description else currentDescription,
        note = if (currentNote.isBlank() && !suggestedNote.isNullOrBlank()) suggestedNote else currentNote,
    )
}
