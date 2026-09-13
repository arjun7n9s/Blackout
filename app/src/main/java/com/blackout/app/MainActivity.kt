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
import androidx.compose.runtime.collectAsState
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
import com.blackout.app.ui.PhotoPreviewScreen
import com.blackout.app.ui.RedactScreen
import com.blackout.app.ui.RedactViewModel
import com.blackout.app.ui.theme.BlackoutTheme
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
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

        // Does the Gemma file we already ship actually do vision on this device?
        //   adb shell am start -n com.blackout.app/.MainActivity --ez vlm_probe true
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("vlm_probe", false) == true) {
            val fixture = intent.getStringExtra("fixture")
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                val r = com.blackout.app.intelligence.VlmProbe.run(applicationContext, fixture)
                android.util.Log.i(
                    "BlackoutVlm",
                    "PROBE RESULT ok=${r.ok} declaresVision=${r.declaresVision} " +
                        "visionTokenBudget=${r.visionTokenBudget} ms=${r.elapsedMs} " +
                        "detail=${r.detail} reply=${r.reply.take(200)}",
                )
            }
        }

        // Read-only vendor-camera reconnaissance:
        //   adb shell am start -n com.blackout.app/.MainActivity --ez cam_probe true
        if (BuildConfig.DEBUG && intent?.getBooleanExtra("cam_probe", false) == true) {
            com.blackout.app.camera.VendorCameraProbe.run(applicationContext)
        }

        val shared = incomingImage(intent)
        fixtures.value = debugFixture(intent)
        setContent {
            BlackoutTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    val fixture by fixtures.collectAsState()
                    BlackoutApp(sharedImage = shared, fixture = fixture)
                }
            }
        }
    }

    /**
     * A second `--es fixture` into an already-running app re-runs it *warm*.
     *
     * Without this the activity is already on top, `onCreate` never fires again, and the intent is
     * silently swallowed - which made every scripted measurement a cold one, with ~1.8 s of engine
     * load folded into a number that was supposed to be inference.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugFixture(intent)?.let { fixtures.value = it }
    }

    /**
     * Latest debug fixture to analyse. A [MutableStateFlow] rather than a plain value so a second
     * intent reaches the composition; emitting the same bitmap twice is fine because the flow only
     * ever holds the newest one.
     */
    private val fixtures = MutableStateFlow<Bitmap?>(null)

    /**
     * Debug-only: run a pushed image straight through the pipeline, no picker, no URI grant.
     *
     *   adb push tools/testdoc-skew30.png \
     *     /sdcard/Android/data/com.blackout.app/files/fixtures/
     *   adb shell am start -n com.blackout.app/.MainActivity --es fixture testdoc-skew30.png
     *
     * Handing a fixture in as a `file://` VIEW intent is unreliable - scoped storage decides
     * whether the decode sees the bytes, and a silent null looks exactly like a clean page. This
     * reads a file the app owns, so a scripted bank run is reproducible and a missing fixture says
     * so in logcat instead of quietly doing nothing.
     */
    private fun debugFixture(intent: Intent?): Bitmap? {
        if (!BuildConfig.DEBUG) return null
        // The VLM probe borrows the same `fixture` extra to pick its image; don't also start a
        // redact run on top of it.
        if (intent?.getBooleanExtra("vlm_probe", false) == true) return null
        val name = intent?.getStringExtra("fixture") ?: return null
        val dir = File(getExternalFilesDir(null), FIXTURE_DIR)
        // The app has to make this directory itself. One created by `adb shell mkdir`, or
        // implicitly by `adb push <dir>`, belongs to `shell` and the app cannot traverse it - the
        // fixtures list fine over adb and read as absent from in here. Same trap as the NPU
        // bundle; see GenieNpuRuntime.
        dir.mkdirs()
        val file = File(dir, name)
        if (!file.isFile) {
            android.util.Log.w(FIXTURE_TAG, "fixture not found: ${file.absolutePath}")
            return null
        }
        val bitmap = decodeSampledBitmap(file)
        android.util.Log.i(
            FIXTURE_TAG,
            if (bitmap == null) "fixture failed to decode: $name"
            else "fixture $name -> ${bitmap.width}x${bitmap.height}",
        )
        return bitmap
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

    private companion object {
        const val FIXTURE_DIR = "fixtures"
        const val FIXTURE_TAG = "BlackoutFixture"
    }
}

/**
 * Navigation is a single state value rather than Navigation-Compose: there are three destinations
 * and one of them carries a [Bitmap], which is awkward to pass through a nav graph. Swap this out
 * once there are real routes to deep-link into.
 *
 * [Camera] is the root. Blackout does one thing, so it opens on the thing - the menu screen that
 * used to sit in front offered exactly two destinations, and both are controls on the camera now.
 */
private sealed interface Screen {
    data object Camera : Screen
    /** Capture review: keep or retake, before spending inference on it. */
    data class Preview(val photo: Bitmap) : Screen
    /** OCR + cascade + redaction surface. */
    data object Redact : Screen
}

@Composable
private fun BlackoutApp(sharedImage: Uri? = null, fixture: Bitmap? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbars = remember { SnackbarHostState() }

    var screen by remember { mutableStateOf<Screen>(Screen.Camera) }
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

    // Debug fixture: skip straight to redaction, same entry point a shared image uses.
    LaunchedEffect(fixture) {
        val bitmap = fixture ?: return@LaunchedEffect
        redactViewModel.start(bitmap)
        screen = Screen.Redact
    }

    val galleryPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bitmap = decodeSampledBitmap(context, uri)
        if (bitmap == null) toast(context.getString(R.string.load_failed))
        else screen = Screen.Preview(bitmap)
    }

    // Back returns to the viewfinder from anywhere; from the viewfinder it leaves the app.
    BackHandler(enabled = screen !is Screen.Camera) { screen = Screen.Camera }

    Box(Modifier.fillMaxSize()) {
        when (val current = screen) {
            Screen.Camera -> CameraScreen(
                onCaptured = { screen = Screen.Preview(it) },
                onPickFromGallery = {
                    galleryPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                // Stay put on a camera error. Bouncing to another screen used to hide the very
                // viewfinder the message is about, and there is nowhere better to go now.
                onError = { message -> toast(message) },
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
