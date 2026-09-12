package com.blackout.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.blackout.app.intelligence.npu.GenieNpuProbe
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.blackout.app.camera.decodeSampledBitmap
import com.blackout.app.share.ShareRedacted
import com.blackout.app.ui.CameraScreen
import com.blackout.app.ui.HomeScreen
import com.blackout.app.ui.PhotoPreviewScreen
import com.blackout.app.ui.RedactScreen
import com.blackout.app.ui.RedactViewModel
import com.blackout.app.ui.rememberCameraPermissionState
import com.blackout.app.ui.theme.BlackoutTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Debug-only NPU proof hook. Never reachable in a release build, never touches the
        // redact path, and cannot change the HUD:
        //   adb shell am start -n com.blackout.app/.MainActivity --ez npu_probe true
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("npu_probe", false) == true) {
            lifecycleScope.launch {
                val r = GenieNpuProbe.run(applicationContext)
                android.util.Log.i(
                    "BlackoutNpu",
                    "PROBE RESULT ok=${r.ok} detail=${r.detail} tokens=${r.tokens} " +
                        "ms=${r.elapsedMs} tok_per_s=${"%.2f".format(r.tokensPerSecond)}",
                )
            }
        }

        val shared = incomingImage(intent)
        setContent {
            BlackoutTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    BlackoutApp(sharedImage = shared)
                }
            }
        }
    }

    /** An image handed to us by another app via ACTION_SEND or ACTION_VIEW. */
    private fun incomingImage(intent: Intent?): Uri? {
        if (intent == null) return null
        if (intent.type?.startsWith("image/") != true) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(
                intent, Intent.EXTRA_STREAM, Uri::class.java
            )
            Intent.ACTION_VIEW -> intent.data
            else -> null
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
    /** Capture review: keep or retake, before spending inference on it. */
    data class Preview(val photo: Bitmap) : Screen
    /** OCR + cascade + redaction surface. */
    data object Redact : Screen
}

@Composable
private fun BlackoutApp(sharedImage: Uri? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    var awaitingPermission by remember { mutableStateOf(false) }

    val permission = rememberCameraPermissionState()
    val redactViewModel: RedactViewModel = viewModel()

    fun toast(message: String) {
        scope.launch { snackbars.showSnackbar(message) }
    }

    // An image shared in from another app skips capture and goes straight to redaction.
    LaunchedEffect(sharedImage) {
        val uri = sharedImage ?: return@LaunchedEffect
        val bitmap = decodeSampledBitmap(context, uri)
        if (bitmap == null) {
            toast(context.getString(R.string.load_failed))
        } else {
            redactViewModel.start(bitmap)
            screen = Screen.Redact
        }
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
                onUsePhoto = {
                    redactViewModel.start(current.photo)
                    screen = Screen.Redact
                },
            )

            Screen.Redact -> RedactScreen(
                viewModel = redactViewModel,
                onRetake = {
                    redactViewModel.reset()
                    screen = Screen.Camera
                },
                onShare = {
                    scope.launch {
                        val redacted = redactViewModel.renderRedacted()
                        if (redacted == null) {
                            toast(context.getString(R.string.share_failed))
                        } else {
                            runCatching {
                                ShareRedacted.share(
                                    context = context,
                                    redacted = redacted,
                                    chooserTitle = context.getString(R.string.share_chooser),
                                )
                            }.onFailure { toast(context.getString(R.string.share_failed)) }
                        }
                    }
                },
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
