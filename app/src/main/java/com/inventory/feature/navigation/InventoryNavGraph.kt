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
 * Root navigation graph of the hierarchical inventory: the browse screen
 * is reused for the root ("inventory", nodeId 0) and for every container
 * ("container/{nodeId}", any depth), with the camera and cover-capture
 * screens saving into the addressed node and the global Search reached
 * from the app bar.
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
                nodeId = 0L,
                onOpenContainer = { containerId ->
                    navController.navigate(Screens.buildContainerRoute(containerId))
                },
                onOpenCamera = { nodeId ->
                    navController.navigate(Screens.buildCameraRoute(nodeId))
                },
                onOpenCoverCapture = { nodeId ->
                    navController.navigate(Screens.buildCoverRoute(nodeId))
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
                onOpenPhoto = { nodeId ->
                    navController.navigate(Screens.buildContainerRoute(nodeId))
                },
            )
        }

        composable(
            route = Screens.Container.route,
            arguments = listOf(
                navArgument(Screens.ARG_NODE_ID) { type = NavType.LongType },
            ),
        ) { backStackEntry ->
            val nodeId = backStackEntry.arguments?.getLong(Screens.ARG_NODE_ID) ?: 0L
            ContainerScreen(
                nodeId = nodeId,
                onOpenContainer = { containerId ->
                    navController.navigate(Screens.buildContainerRoute(containerId))
                },
                onOpenCamera = { navController.navigate(Screens.buildCameraRoute(nodeId)) },
                onOpenCoverCapture = { containerId ->
                    navController.navigate(Screens.buildCoverRoute(containerId))
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
                navArgument(Screens.ARG_NODE_ID) { type = NavType.LongType },
            ),
        ) {
            CameraCaptureScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route = Screens.CoverCapture.route,
            arguments = listOf(
                navArgument(Screens.ARG_NODE_ID) { type = NavType.LongType },
            ),
        ) {
            CoverCaptureScreen(onBack = { navController.popBackStack() })
        }
    }
}
