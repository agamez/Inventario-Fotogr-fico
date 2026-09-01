package com.inventory.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inventory.feature.boxes.BoxesScreen
import com.inventory.feature.locations.LocationsScreen
import com.inventory.feature.photos.CameraCaptureScreen
import com.inventory.feature.photos.PhotosScreen

/**
 * Root navigation graph connecting Locations -> Boxes -> Photos -> Camera.
 */
@Composable
fun InventoryNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Screens.Locations.route,
        modifier = modifier,
    ) {
        composable(route = Screens.Locations.route) {
            LocationsScreen(
                onOpenLocation = { locationId ->
                    navController.navigate(Screens.buildBoxesRoute(locationId))
                },
            )
        }

        composable(
            route = Screens.Boxes.route,
            arguments = listOf(
                navArgument(Screens.ARG_LOCATION_ID) { type = NavType.LongType },
            ),
        ) {
            BoxesScreen(
                onOpenBox = { boxId ->
                    navController.navigate(Screens.buildPhotosRoute(boxId))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screens.Photos.route,
            arguments = listOf(
                navArgument(Screens.ARG_BOX_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val boxId = backStackEntry.arguments?.getLong(Screens.ARG_BOX_ID) ?: -1L
            PhotosScreen(
                onOpenCamera = {
                    navController.navigate(Screens.buildCameraRoute(boxId))
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screens.Camera.route,
            arguments = listOf(
                navArgument(Screens.ARG_BOX_ID) { type = NavType.LongType },
            ),
        ) {
            CameraCaptureScreen(onBack = { navController.popBackStack() })
        }
    }
}
