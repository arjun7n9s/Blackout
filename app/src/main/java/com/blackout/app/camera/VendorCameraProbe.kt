package com.blackout.app.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log

/**
 * Read-only reconnaissance: can a third-party app drive vivo's "Ultra HD Document" capture mode?
 *
 * The HAL clearly has it. `dumpsys media.camera` lists vivo vendor tags on this handset:
 *
 * ```
 * 0x811700a6 (document.pack)            byte   vivo.control
 * 0x811700b8 (document.area)            int32  vivo.control
 * 0x811700b9 (document.aiSceneType)     int32  vivo.control
 * 0x811700c5 (document.tween_animation) byte   vivo.control
 * 0x81260005 (capture.document)         int32  vivo.capability
 * ```
 *
 * Tags being *defined* is not the same as being *settable*. Android publishes the vendor tag
 * descriptor to every client, but the camera HAL decides which keys it honours in a
 * CaptureRequest, and OEMs routinely accept privileged keys only from their own camera package.
 * `CameraCharacteristics.getAvailableCaptureRequestKeys()` is the authoritative answer, and it can
 * only be asked on device.
 *
 * So this probe enumerates and logs; it changes nothing and is never on the redact path.
 */
object VendorCameraProbe {

    private const val TAG = "BlackoutCam"

    fun run(context: Context) {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: run {
            Log.w(TAG, "no CameraManager")
            return
        }

        for (id in runCatching { manager.cameraIdList }.getOrDefault(emptyArray())) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            Log.i(TAG, "--- camera $id (facing=$facing) ---")

            // Static characteristics the HAL exposes to us.
            val vendorChars = chars.keys.filter { it.name.interesting() }
            Log.i(TAG, "vendor characteristics visible: ${vendorChars.size}")
            vendorChars.take(20).forEach { key ->
                val value = runCatching { chars.get(key) }.getOrNull()
                Log.i(TAG, "  CHAR ${key.name} = ${render(value)}")
            }

            // The list that actually decides whether we may set it in a CaptureRequest.
            val settable = runCatching { chars.availableCaptureRequestKeys }
                .getOrDefault(emptyList())
            val vendorSettable = settable.filter { it.name.interesting() }
            Log.i(
                TAG,
                "settable request keys: ${settable.size} total, ${vendorSettable.size} vivo/document",
            )
            vendorSettable.forEach { Log.i(TAG, "  SETTABLE ${it.name}") }
            val docKeys = vendorSettable.filter { it.name.lowercase().contains("document") }
            Log.i(TAG, "  >>> DOCUMENT KEYS SETTABLE: ${docKeys.size} ${docKeys.map { it.name }}")

            if (vendorSettable.isEmpty()) {
                Log.i(TAG, "  => no vivo capture keys settable by this app on camera $id")
            }
        }
    }

    private fun String.interesting(): Boolean {
        val n = lowercase()
        return n.contains("vivo") || n.contains("document") || n.contains("scene.mode") ||
            n.contains("scenemode")
    }

    private fun render(value: Any?): String = when (value) {
        null -> "null"
        is IntArray -> value.joinToString(",", "[", "]")
        is ByteArray -> value.joinToString(",", "[", "]")
        is Array<*> -> value.joinToString(",", "[", "]")
        else -> value.toString()
    }
}
