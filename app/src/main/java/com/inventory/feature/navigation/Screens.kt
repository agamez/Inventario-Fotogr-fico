package com.inventory.feature.navigation

import android.net.Uri

/**
 * Route contracts for the app's navigation graph.
 *
 * The filesystem made paths the identity of everything, and dual-mode
 * storage made those paths RELATIVE (root = `""`, e.g.
 * "Building/Storage unit") so one route means the same entry under the
 * SAF tree and under `/sdcard/PhotoInventory`. The arguments below are
 * relative folder paths, URL-encoded on the way in ([buildContainerRoute]
 * and friends — never with the empty root path: the root has its own
 * [Inventory] destination) and automatically decoded by Navigation into
 * each feature ViewModel's `SavedStateHandle` under [ARG_PATH] — the
 * contract every ViewModel reads.
 */
sealed class Screens(val route: String) {

    /** Home: the top-level containers of the inventory root folder. */
    data object Inventory : Screens(route = "inventory")

    /** Browse screen for one container folder: children, photos, actions. */
    data object Container : Screens(route = "container/{$ARG_PATH}")

    /** Camera capture screen, saving into a given container folder. */
    data object Camera : Screens(route = "camera/{$ARG_PATH}")

    /** Camera capture screen, saving the cover of a given container folder. */
    data object CoverCapture : Screens(route = "cover/{$ARG_PATH}")

    /** Global search across every indexed photo. */
    data object Search : Screens(route = "search")

    /** AI auto-labeling configuration (endpoint, key, model, test). */
    data object Settings : Screens(route = "settings")

    /** Trash: restorable soft-deleted containers and photos. */
    data object Trash : Screens(route = "trash")

    companion object {
        const val ARG_PATH = "path"

        /** Builds a concrete [Container] route for the folder at [relPath] (non-empty). */
        fun buildContainerRoute(relPath: String) = "container/" + Uri.encode(relPath)

        /** Builds a concrete [Camera] route saving into the folder at [relPath] (non-empty). */
        fun buildCameraRoute(relPath: String) = "camera/" + Uri.encode(relPath)

        /** Builds a concrete [CoverCapture] route for the folder at [relPath] (non-empty). */
        fun buildCoverRoute(relPath: String) = "cover/" + Uri.encode(relPath)
    }
}
