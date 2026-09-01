package com.inventory.feature.navigation

/**
 * Route contracts for the app's navigation graph.
 *
 * The argument names [ARG_LOCATION_ID] and [ARG_BOX_ID] are contractual: the
 * feature ViewModels read them from their `SavedStateHandle`, so they must
 * match the placeholders used in the route patterns below.
 */
sealed class Screens(val route: String) {

    /** Entry point: list of storage locations. */
    data object Locations : Screens(route = "locations")

    /** List of boxes inside a given location. */
    data object Boxes : Screens(route = "boxes/{$ARG_LOCATION_ID}")

    /** Photo grid of a given box. */
    data object Photos : Screens(route = "photos/{$ARG_BOX_ID}")

    /** Camera capture screen, saving into a given box. */
    data object Camera : Screens(route = "camera/{$ARG_BOX_ID}")

    companion object {
        const val ARG_LOCATION_ID = "locationId"
        const val ARG_BOX_ID = "boxId"

        /** Builds a concrete [Boxes] route for the given location id. */
        fun buildBoxesRoute(locationId: Long): String = "boxes/$locationId"

        /** Builds a concrete [Photos] route for the given box id. */
        fun buildPhotosRoute(boxId: Long): String = "photos/$boxId"

        /** Builds a concrete [Camera] route for the given box id. */
        fun buildCameraRoute(boxId: Long): String = "camera/$boxId"
    }
}
