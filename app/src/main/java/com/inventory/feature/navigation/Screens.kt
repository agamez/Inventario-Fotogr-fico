package com.inventory.feature.navigation

import android.net.Uri

/**
 * Route contracts for the app's navigation graph.
 *
 * The filesystem made paths the identity of everything, so the arguments
 * below are ABSOLUTE FILE PATHS, URL-encoded on the way in ([buildContainerRoute]
 * and friends) and automatically decoded by Navigation into each feature
 * ViewModel's `SavedStateHandle` under [ARG_PATH] — the contract every
 * ViewModel reads. `inventory` is the storage root itself and takes no
 * arguments.
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

    companion object {
        const val ARG_PATH = "path"

        /** Builds a concrete [Container] route for the folder at [path]. */
        fun buildContainerRoute(path: String) = "container/" + Uri.encode(path)

        /** Builds a concrete [Camera] route saving into the folder at [path]. */
        fun buildCameraRoute(path: String) = "camera/" + Uri.encode(path)

        /** Builds a concrete [CoverCapture] route for the folder at [path]. */
        fun buildCoverRoute(path: String) = "cover/" + Uri.encode(path)
    }
}
