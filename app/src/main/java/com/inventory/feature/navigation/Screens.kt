package com.inventory.feature.navigation

/**
 * Route contracts for the app's navigation graph.
 *
 * The argument names [ARG_LOCATION_ID], [ARG_BOX_ID], [ARG_TARGET] and
 * [ARG_COVER_ID] are contractual: the feature ViewModels read them from
 * their `SavedStateHandle`, so they must match the placeholders used in
 * the route patterns below.
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

    /** Camera capture screen, saving the cover of a box or a location. */
    data object CoverCapture : Screens(route = "cover/{$ARG_TARGET}/{$ARG_COVER_ID}")

    /** Global search across every photo, box and location. */
    data object Search : Screens(route = "search")

    companion object {
        const val ARG_LOCATION_ID = "locationId"
        const val ARG_BOX_ID = "boxId"
        const val ARG_TARGET = "target"
        const val ARG_COVER_ID = "id"

        /** Builds a concrete [Boxes] route for the given location id. */
        fun buildBoxesRoute(locationId: Long): String = "boxes/$locationId"

        /** Builds a concrete [Photos] route for the given box id. */
        fun buildPhotosRoute(boxId: Long): String = "photos/$boxId"

        /** Builds a concrete [Camera] route for the given box id. */
        fun buildCameraRoute(boxId: Long): String = "camera/$boxId"

        /** Builds a concrete [CoverCapture] route for a "box" or "location" target. */
        fun buildCoverRoute(target: String, id: Long): String = "cover/$target/$id"
    }
}
