package com.inventory.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/** Drop-target id prefix of the ancestor chips under the action bar. */
const val ANCESTOR_TARGET_PREFIX = "ancestor:"

/** What a drag is carrying: photos (default) or a single container folder. */
enum class DragKind { PHOTO, CONTAINER }

/** What a drag is carrying: the kind, the relative paths and the ghost label. */
data class DragPayload(
    val relPaths: List<String>,
    val label: String,
    val kind: DragKind = DragKind.PHOTO,
)

/**
 * Session state of an in-progress desktop drag: the payload, the current
 * pointer position, the target currently hovered and the bounds of every
 * registered drop target keyed as `"$owner|$id"`. It is the mouse-adapted
 * twin of the Android `DragController` and lives at the [App] root, so the
 * ghost overlay, the drop resolution and the browse grid all see the same
 * instance — and an in-flight session survives a spring-loaded folder
 * change (the root-level pointer tracker in [App] owns it from then on).
 */
class DragController {
    var payload by mutableStateOf<DragPayload?>(null)
    var pointer by mutableStateOf<Offset?>(null)
    var hoveredTarget by mutableStateOf<String?>(null)
    var targets by mutableStateOf<Map<String, Rect>>(emptyMap())

    /**
     * Container paths that must not act as drop targets during the current
     * drag: the dragged container itself plus its whole subtree (a move into
     * the subtree would create a cycle). Empty for photo drags.
     */
    var blockedTargets by mutableStateOf<Set<String>>(emptySet())

    /**
     * Drop resolver installed by [App]: called on release with the payload
     * and the hovered target id (`null` when the release lands on no target).
     * Resolution happens before the session is torn down.
     */
    var onDrop: ((DragPayload, String?) -> Unit)? = null

    fun start(payload: DragPayload) {
        this.payload = payload
        blockedTargets = emptySet()
        hoveredTarget = null
    }

    /** Resolves the payload against the hovered target, then ends the session. */
    fun drop() {
        val p = payload
        if (p != null) onDrop?.invoke(p, hoveredTarget)
        end()
    }

    fun end(): DragPayload? {
        val p = payload
        payload = null
        pointer = null
        hoveredTarget = null
        blockedTargets = emptySet()
        return p
    }

    fun clearTargets(owner: String) {
        targets = targets.filterKeys { !it.startsWith("$owner|") }
    }

    fun registerTarget(owner: String, id: String, rect: Rect) {
        targets = targets + ("$owner|$id" to rect)
    }
}
