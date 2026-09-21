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
import com.inventory.feature.settings.SettingsScreen

/**
 * Root navigation graph of the filesystem inventory: the browse screen is
 * reused for the storage root ("inventory") and for any container folder
 * ("container/{path}", URL-encoded RELATIVE path, any depth), with the
 * camera and cover-capture screens saving into the addressed folder and
 * the global Search reached from the app bar. A search hit living
 * directly in the root jumps to "inventory" (its relative path is empty,
 * so no container route exists for it).
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
                onOpenContainer = { relPath ->
                    navController.navigate(Screens.buildContainerRoute(relPath))
                },
                onOpenCamera = { relPath ->
                    navController.navigate(Screens.buildCameraRoute(relPath))
                },
                onOpenCoverCapture = { relPath ->
                    navController.navigate(Screens.buildCoverRoute(relPath))
                },
                onOpenSearch = {
                    navController.navigate(Screens.Search.route)
                },
                onOpenSettings = {
                    navController.navigate(Screens.Settings.route)
                },
                onBack = {},
            )
        }

        composable(route = Screens.Settings.route) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable(route = Screens.Search.route) {
            SearchScreen(
                onBack = { navController.popBackStack() },
                onOpenPhoto = { containerRel ->
                    if (containerRel.isEmpty()) {
                        navController.navigate(Screens.Inventory.route)
                    } else {
                        navController.navigate(Screens.buildContainerRoute(containerRel))
                    }
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
                onOpenContainer = { relPath ->
                    navController.navigate(Screens.buildContainerRoute(relPath))
                },
                onOpenCamera = { relPath -> navController.navigate(Screens.buildCameraRoute(relPath)) },
                onOpenCoverCapture = { relPath ->
                    navController.navigate(Screens.buildCoverRoute(relPath))
                },
                onOpenSearch = {
                    navController.navigate(Screens.Search.route)
                },
                onOpenSettings = {
                    navController.navigate(Screens.Settings.route)
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
