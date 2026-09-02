package com.inventory.feature.navigation

/**
 * Route contracts for the app's navigation graph.
 *
 * The argument name [ARG_NODE_ID] is contractual: the feature ViewModels
 * read it from their `SavedStateHandle`, so it must match the placeholder
 * used in the route patterns below. `inventory` is the virtual root of the
 * container tree (nodeId 0) and therefore takes no arguments.
 */
sealed class Screens(val route: String) {

    /** Home: the top-level containers of the inventory. */
    data object Inventory : Screens(route = "inventory")

    /** Browse screen for one container: children, photos, actions. */
    data object Container : Screens(route = "container/{$ARG_NODE_ID}")

    /** Camera capture screen, saving into a given container. */
    data object Camera : Screens(route = "camera/{$ARG_NODE_ID}")

    /** Camera capture screen, saving the cover of a given container. */
    data object CoverCapture : Screens(route = "cover/{$ARG_NODE_ID}")

    /** Global search across every photo and container path. */
    data object Search : Screens(route = "search")

    companion object {
        const val ARG_NODE_ID = "nodeId"

        /** Builds a concrete [Container] route for the given container id. */
        fun buildContainerRoute(id: Long) = "container/$id"

        /** Builds a concrete [Camera] route saving into [nodeId]. */
        fun buildCameraRoute(nodeId: Long) = "camera/$nodeId"

        /** Builds a concrete [CoverCapture] route for [nodeId]'s cover. */
        fun buildCoverRoute(nodeId: Long) = "cover/$nodeId"
    }
}
