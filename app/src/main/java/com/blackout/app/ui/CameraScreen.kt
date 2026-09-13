package com.blackout.app.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.blackout.app.R
import com.blackout.app.camera.awaitCameraProvider
import com.blackout.app.camera.buildImageCapture
import com.blackout.app.camera.captureBitmap

/**
 * The app's front door.
 *
 * Blackout does one thing, so it opens on the thing. A menu screen in front of a single-purpose
 * tool costs a tap on every use and teaches nothing, and the two destinations it offered - camera
 * and gallery - both fit on this screen as controls.
 *
 * ## Why there is no photo thumbnail in the gallery slot
 *
 * Camera apps put the last shot there. Doing that needs `READ_MEDIA_IMAGES`: permanent access to
 * the entire photo library, requested on first launch, to render a 48dp square. For an app whose
 * whole claim is that your documents never leave the device, that trade is indefensible - and
 * unnecessary, because [androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia]
 * returns exactly one user-chosen image with **no permission at all**. Same position, same
 * gesture, none of the access.
 *
 * ## Denied camera is not a dead end
 *
 * If the permission is refused the preview is replaced by a rationale *and the gallery button
 * stays live*, so the app still works. That is also why the permission surface lives here rather
 * than on a screen in front: there is nothing useful to show before it.
 */
@Composable
fun CameraScreen(
    onCaptured: (Bitmap) -> Unit,
    onPickFromGallery: () -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = rememberCameraPermissionState()

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    var capturing by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val imageCapture = remember { buildImageCapture() }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    // Binding is gated on the grant: CameraX throws if asked to bind without it, and the failure
    // path would fire onError before the user has even answered the dialog.
    LaunchedEffect(lensFacing, lifecycleOwner, permission.granted) {
        if (!permission.granted) return@LaunchedEffect
        runCatching {
            val cameraProvider = context.awaitCameraProvider()
            provider = cameraProvider

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                preview,
                imageCapture,
            )
        }.onFailure { t ->
            Log.e("Blackout", "camera bind failed", t)
            onError(context.getString(R.string.camera_failed))
        }
    }

    DisposableEffect(Unit) { onDispose { provider?.unbindAll() } }

    Column(modifier.fillMaxSize().background(Color.Black)) {

        // The promise, stated where the document is still in the user's hands rather than after
        // the fact. This is the moment it is worth the pixels.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(shape = RoundedCornerShape(50), color = Color.White.copy(alpha = 0.08f)) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_shield),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.75f),
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(R.string.camera_privacy_chip),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (permission.granted) {
                RoundControl(
                    icon = R.drawable.ic_switch_camera,
                    description = stringResource(R.string.switch_camera),
                    size = 38.dp,
                    onClick = {
                        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                            CameraSelector.LENS_FACING_FRONT
                        } else {
                            CameraSelector.LENS_FACING_BACK
                        }
                    },
                )
            }
        }

        // Viewfinder. Rounded and inset rather than full-bleed, so the frame reads as a document
        // window and the controls below get their own black field instead of floating over glass.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(Color(0xFF0C0C0E)),
            contentAlignment = Alignment.Center,
        ) {
            if (permission.granted) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                CameraPermissionPrompt(
                    onAllow = permission.request,
                    onPickFromGallery = onPickFromGallery,
                )
            }
        }

        Text(
            stringResource(
                if (permission.granted) R.string.camera_hint else R.string.camera_hint_blocked
            ),
            color = Color.White.copy(alpha = 0.42f),
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 10.dp),
        )

        // Gallery, shutter, and a spacer that keeps the shutter optically centred. Weighted rather
        // than SpaceBetween so the shutter sits on the true centre line whatever flanks it.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 34.dp, end = 34.dp, top = 4.dp, bottom = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GalleryButton(onClick = onPickFromGallery)

            ShutterButton(
                enabled = permission.granted && !capturing,
                onClick = {
                    capturing = true
                    imageCapture.captureBitmap(
                        context = context,
                        mirror = lensFacing == CameraSelector.LENS_FACING_FRONT,
                        onCaptured = {
                            capturing = false
                            onCaptured(it)
                        },
                        onError = {
                            capturing = false
                            onError(context.getString(R.string.capture_failed))
                        },
                    )
                },
            )

            // Balances the gallery button. Empty on purpose - a third control here would be one
            // the user does not need while pointing at a document.
            Spacer(Modifier.size(54.dp))
        }
    }
}

@Composable
private fun CameraPermissionPrompt(
    onAllow: () -> Unit,
    onPickFromGallery: () -> Unit,
) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_camera),
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.5f),
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(
            stringResource(R.string.camera_permission_title),
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.camera_permission_body),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Button(onClick = onAllow, shape = RoundedCornerShape(14.dp)) {
            Text(stringResource(R.string.camera_permission_allow))
        }
        TextButton(onClick = onPickFromGallery) {
            Text(
                stringResource(R.string.camera_permission_gallery),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 12.sp,
            )
        }
    }
}

/** Where a camera app puts the last shot. See the class note on why there is no thumbnail here. */
@Composable
private fun GalleryButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(54.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(15.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_image),
            contentDescription = stringResource(R.string.pick_from_gallery),
            tint = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.size(23.dp),
        )
    }
}

@Composable
private fun RoundControl(
    icon: Int,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(size * 0.48f),
        )
    }
}

/**
 * Ring with a filled core, dipping slightly while a capture is in flight.
 *
 * The dip is the only feedback that the shutter was heard: capture takes long enough to feel
 * unresponsive, and a disabled-looking button with no movement reads as a broken one.
 */
@Composable
private fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(if (enabled) 1f else 0.9f, label = "shutter")
    val ring = Color.White.copy(alpha = if (enabled) 1f else 0.35f)
    Box(
        modifier = modifier
            .size(76.dp)
            .scale(scale)
            .border(3.dp, ring, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(7.dp)
            .background(color = ring, shape = CircleShape),
    )
}
