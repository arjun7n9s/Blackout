package com.blackout.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The "no network" claim, asserted rather than commented.
 *
 * Phone C's P0: `dumpsys package com.blackout.app` on the installed APK listed
 * `android.permission.INTERNET: granted=true` plus `ACCESS_NETWORK_STATE`, because ML Kit pulls
 * `com.google.android.datatransport:transport-backend-cct` and LiteRT pulls
 * `androidx.media3:media3-common`, and both merge those permissions in. The app's own manifest
 * said nothing about them, so nobody noticed until someone read the merger report.
 *
 * Both are now `tools:node="remove"`d. This test fails if either the removal or the `tools`
 * namespace that makes it work is ever dropped, and - when a build has already run - it also
 * checks the *merged* manifest, which is the artifact that actually ships.
 */
class ManifestPolicyTest {

    private val stripped = listOf(
        "android.permission.INTERNET",
        "android.permission.ACCESS_NETWORK_STATE",
    )

    private fun sourceManifest(): File {
        val candidates = listOf(
            File("src/main/AndroidManifest.xml"),
            File("app/src/main/AndroidManifest.xml"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
    }

    @Test
    fun `network permissions are explicitly removed from the merge`() {
        val manifest = sourceManifest().readText()
        assertTrue(
            "the tools namespace is what makes node=remove work",
            manifest.contains("xmlns:tools=\"http://schemas.android.com/tools\""),
        )
        for (permission in stripped) {
            val declaration = Regex(
                """<uses-permission[^>]*android:name="${Regex.escape(permission)}"[^>]*/>"""
            ).find(manifest)
            assertNotNull("$permission must be declared so it can be removed", declaration)
            assertTrue(
                "$permission must carry tools:node=\"remove\"",
                declaration!!.value.contains("tools:node=\"remove\""),
            )
        }
    }

    @Test
    fun `the merged manifest holds no network permission`() {
        val merged = listOf(
            "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
            "app/build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml",
        ).map(::File).firstOrNull { it.isFile }
        // Nothing to check on a clean checkout; the source-manifest test above still holds.
            ?: return

        val text = merged.readText()
        for (permission in stripped) {
            assertFalse(
                "$permission survived the merge into ${merged.path}",
                text.contains(permission),
            )
        }
    }
}
