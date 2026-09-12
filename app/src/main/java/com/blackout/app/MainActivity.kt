package com.blackout.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.blackout.app.camera.decodeSampledBitmap
import com.blackout.app.ui.CameraScreen
import com.blackout.app.ui.HomeScreen
import com.blackout.app.ui.PhotoPreviewScreen
import com.blackout.app.ui.rememberCameraPermissionState
import com.blackout.app.ui.theme.BlackoutTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BlackoutTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    BlackoutApp()
                }
            }
        }
    }
}

/**
 * v0 navigation is a single state value rather than Navigation-Compose: there are three
 * destinations and one of them carries a [Bitmap], which is awkward to pass through a nav graph.
 * Swap this out once there are real routes to deep-link into.
 */
private sealed interface Screen {
    data object Home : Screen
    data object Camera : Screen
    data class Preview(val photo: Bitmap) : Screen
}

@Composable
private fun BlackoutApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var awaitingPermission by remember { mutableStateOf(false) }

    val permission = rememberCameraPermissionState()

    fun toast(message: String) {
        scope.launch { snackbars.showSnackbar(message) }
    }

    // If the user tapped "Take photo" before granting, open the camera as soon as they allow it.
    LaunchedEffect(permission.granted, awaitingPermission) {
        if (awaitingPermission && permission.granted) {
            awaitingPermission = false
            screen = Screen.Camera
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bitmap = decodeSampledBitmap(context, uri)
        if (bitmap == null) toast(context.getString(R.string.load_failed))
        else screen = Screen.Preview(bitmap)
    }

    BackHandler(enabled = screen !is Screen.Home) { screen = Screen.Home }

    Box(Modifier.fillMaxSize()) {
        when (val current = screen) {
            Screen.Home -> HomeScreen(
                permission = permission,
                onTakePhoto = {
                    if (permission.granted) {
                        screen = Screen.Camera
                    } else {
                        awaitingPermission = true
                        permission.request()
                    }
                },
                onPickFromGallery = {
                    galleryPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            )

            Screen.Camera -> CameraScreen(
                onCaptured = { screen = Screen.Preview(it) },
                onClose = { screen = Screen.Home },
                onError = { message ->
                    screen = Screen.Home
                    toast(message)
                },
            )

            is Screen.Preview -> PhotoPreviewScreen(
                photo = current.photo,
                onRetake = { screen = Screen.Camera },
                // v0 stops here on purpose - the redaction pass is the next milestone.
                onUsePhoto = { toast(context.getString(R.string.v0_notice)) },
            )
        }

        SnackbarHost(
            hostState = snackbars,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }
}
