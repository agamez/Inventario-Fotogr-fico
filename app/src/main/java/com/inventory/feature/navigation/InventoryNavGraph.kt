package com.inventory.feature.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.inventory.feature.containers.ContainerScreen
import com.inventory.feature.containers.drag.DragController
import com.inventory.feature.containers.drag.DragKind
import com.inventory.feature.photos.CameraCaptureScreen
import com.inventory.feature.photos.CoverCaptureScreen
import com.inventory.feature.search.SearchScreen
import com.inventory.feature.settings.SettingsScreen
import com.inventory.feature.trash.TrashScreen
import kotlin.math.roundToInt

/**
 * Root navigation graph of the filesystem inventory: the browse screen is
 * reused for the storage root ("inventory") and for any container folder
 * ("container/{path}", URL-encoded RELATIVE path, any depth), with the
 * camera and cover-capture screens saving into the addressed folder and
 * the global Search reached from the app bar. A search hit living
 * directly in the root jumps to "inventory" (its relative path is empty,
 * so no container route exists for it).
 *
 * The whole graph sits inside a [Box] so an in-progress drag can be
 * represented by a floating ghost card above every destination; the
 * [DragController] session is shared with the container destination only.
 */
@Composable
fun InventoryNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val dragController = remember { DragController() }

    Box(
        modifier = modifier
            .fillMaxSize()
            // The gesture source (a photo tile) lives inside a destination
            // that spring-loaded navigation can replace mid-drag. This root
            // node stays composed and observes the pointer in the Initial
            // pass (never consuming), so the in-flight session keeps
            // following the finger and ends with the drop on release.
            .pointerInput(dragController) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (dragController.payload == null) continue
                            event.changes.firstOrNull()?.let { change ->
                                dragController.pointer = change.position
                            }
                            if (event.changes.none { it.pressed }) dragController.drop()
                        }
                    }
                } finally {
                    if (dragController.payload != null) dragController.end()
                }
            },
    ) {
        NavHost(
            navController = navController,
            startDestination = Screens.Inventory.route,
            modifier = Modifier.fillMaxSize(),
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
                    onOpenTrash = {
                        navController.navigate(Screens.Trash.route)
                    },
                    onBack = {},
                    dragController = dragController,
                )
            }

            composable(route = Screens.Settings.route) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(route = Screens.Trash.route) {
                TrashScreen(onBack = { navController.popBackStack() })
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
                    onOpenTrash = {
                        navController.navigate(Screens.Trash.route)
                    },
                    onBack = { navController.popBackStack() },
                    dragController = dragController,
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

        dragController.payload?.let { payload ->
            dragController.pointer?.let { point ->
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    shadowElevation = 8.dp,
                    modifier = Modifier
                        .offset { IntOffset(point.x.roundToInt(), point.y.roundToInt()) }
                        .size(72.dp),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (payload.kind == DragKind.CONTAINER) {
                            Icon(
                                imageVector = Icons.Outlined.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                        Text(
                            text = payload.label,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (payload.kind == DragKind.PHOTO) {
                            Text(
                                text = payload.relPaths.size.toString(),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}
