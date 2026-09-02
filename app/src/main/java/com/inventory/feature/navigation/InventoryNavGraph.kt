package com.inventory.feature.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inventory.feature.containers.ContainerScreen
import com.inventory.feature.photos.CameraCaptureScreen
import com.inventory.feature.photos.CoverCaptureScreen
import com.inventory.feature.search.SearchScreen

/**
 * Root navigation graph of the filesystem inventory: the browse screen is
 * reused for the storage root ("inventory") and for any container folder
 * ("container/{path}", URL-encoded absolute path, any depth), with the
 * camera and cover-capture screens saving into the addressed folder and
 * the global Search reached from the app bar.
 */
@Composable
fun InventoryNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(
        navController = navController,
        startDestination = Screens.Inventory.route,
        modifier = modifier,
    ) {
        composable(route = Screens.Inventory.route) {
            ContainerScreen(
                onOpenContainer = { path ->
                    navController.navigate(Screens.buildContainerRoute(path))
                },
                onOpenCamera = { path ->
                    navController.navigate(Screens.buildCameraRoute(path))
                },
                onOpenCoverCapture = { path ->
                    navController.navigate(Screens.buildCoverRoute(path))
                },
                onOpenSearch = {
                    navController.navigate(Screens.Search.route)
                },
                onBack = {},
            )
        }

        composable(route = Screens.Search.route) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenPhoto = { containerPath ->
                    navController.navigate(Screens.buildContainerRoute(containerPath))
                },
            )
        }

        composable(
            route = Screens.Container.route,
            arguments = listOf(
                navArgument(Screens.ARG_PATH) { type = NavType.StringType },
            ),
        ) {
            ContainerScreen(
                onOpenContainer = { path ->
                    navController.navigate(Screens.buildContainerRoute(path))
                },
                onOpenCamera = { navController.navigate(Screens.buildCameraRoute(it)) },
                onOpenCoverCapture = { path ->
                    navController.navigate(Screens.buildCoverRoute(path))
                },
                onOpenSearch = {
                    navController.navigate(Screens.Search.route)
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = Screens.Camera.route,
            arguments = listOf(
                navArgument(Screens.ARG_PATH) { type = NavType.StringType },
            ),
        ) {
            CameraCaptureScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Screens.CoverCapture.route,
            arguments = listOf(
                navArgument(Screens.ARG_PATH) { type = NavType.StringType },
            ),
        ) {
            CoverCaptureScreen(onBack = { navController.popBackStack() })
        }
    }
}
