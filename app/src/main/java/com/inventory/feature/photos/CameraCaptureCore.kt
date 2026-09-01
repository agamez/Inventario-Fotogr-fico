package com.inventory.feature.photos

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.inventory.R

/**
 * Reusable CameraX capture surface: runtime CAMERA permission gate (first
 * launch auto-request, rationale fallback with Grant/Open settings), a
 * back-camera [PreviewView] bound through [ProcessCameraProvider] for as
 * long as the composable lives, and a shutter button that hands the JPEG
 * bytes to [onImageCaptured]. Any capture- or bind-time failure is reported
 * through [onCameraError] so the host can surface its own snackbar.
 *
 * Everything about *what happens* to the image (saving it into a box,
 * setting a cover, …) belongs to the host screen, not here.
 */
@Composable
fun CameraCaptureCore(
    modifier: Modifier = Modifier,
    onImageCaptured: (ByteArray) -> Unit,
    onCameraError: () -> Unit,
) {
    val context = LocalContext.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasCameraPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // Re-check when resuming, e.g. after granting in system settings.
    LifecycleResumeEffect(hasCameraPermission) {
        hasCameraPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        onPauseOrDispose { }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (hasCameraPermission) {
            val lifecycleOwner = LocalLifecycleOwner.current
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            }
            val imageCapture = remember { ImageCapture.Builder().build() }

            DisposableEffect(lifecycleOwner) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                var cameraProvider: ProcessCameraProvider? = null
                cameraProviderFuture.addListener(
                    {
                        try {
                            val provider = cameraProviderFuture.get()
                            cameraProvider = provider
                            val preview = Preview.Builder().build().apply {
                                setSurfaceProvider(previewView.surfaceProvider)
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                imageCapture,
                            )
                        } catch (exception: Exception) {
                            onCameraError()
                        }
                    },
                    ContextCompat.getMainExecutor(context),
                )
                onDispose { cameraProvider?.unbindAll() }
            }

            AndroidView(
                factory = { previewView },
                modifier = Modifier.fillMaxSize(),
            )

            FloatingActionButton(
                onClick = {
                    imageCapture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                // The proxy is closed on every success
                                // path (try/finally); the single JPEG
                                // plane is copied out before closing.
                                val bytes = try {
                                    val buffer = image.planes.first().buffer
                                    ByteArray(buffer.remaining()).also { buffer.get(it) }
                                } catch (exception: Exception) {
                                    null
                                } finally {
                                    image.close()
                                }
                                if (bytes != null) {
                                    onImageCaptured(bytes)
                                } else {
                                    onCameraError()
                                }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                // This callback receives no ImageProxy,
                                // so there is nothing to close here.
                                onCameraError()
                            }
                        },
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(24.dp)
                    .size(72.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoCamera,
                    contentDescription = stringResource(R.string.take_photo),
                )
            }
        } else {
            CameraPermissionRationale(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onOpenSettings = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun CameraPermissionRationale(
    onGrant: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PhotoCamera,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.outlineVariant,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.camera_permission_rationale),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onGrant) {
            Text(stringResource(R.string.grant_permission))
        }
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(onClick = onOpenSettings) {
            Text(stringResource(R.string.open_settings))
        }
    }
}
