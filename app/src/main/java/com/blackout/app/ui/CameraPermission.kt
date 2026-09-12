package com.blackout.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Immutable
data class CameraPermissionState(
    val granted: Boolean,
    /** Denied at least once, and the OS will still show the dialog if we ask again. */
    val denied: Boolean,
    /** Denied to the point where only Settings can turn it back on. */
    val deniedPermanently: Boolean,
    val request: () -> Unit,
    val openSettings: () -> Unit,
)

/**
 * Minimal CAMERA permission state.
 *
 * The subtlety worth knowing: [ActivityCompat.shouldShowRequestRationale] returns false both
 * *before* the first ask and *after* a permanent denial, so it can only be read as "permanent"
 * once we've actually asked. That's what [asked] guards.
 */
@Composable
fun rememberCameraPermissionState(): CameraPermissionState {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var asked by rememberSaveable { mutableStateOf(false) }
    var denied by remember { mutableStateOf(false) }
    var deniedPermanently by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        asked = true
        granted = isGranted
        denied = !isGranted
        deniedPermanently = !isGranted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA)
    }

    // The user may flip the permission in Settings and come back, so re-read it on resume.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val now = context.hasCameraPermission()
                granted = now
                if (now) {
                    denied = false
                    deniedPermanently = false
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return CameraPermissionState(
        granted = granted,
        denied = denied && !deniedPermanently,
        deniedPermanently = deniedPermanently,
        request = {
            if (deniedPermanently) context.openAppSettings()
            else launcher.launch(Manifest.permission.CAMERA)
        },
        openSettings = { context.openAppSettings() },
    )
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    )
}
