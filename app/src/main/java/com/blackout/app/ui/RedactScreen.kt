package com.blackout.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blackout.app.R
import com.blackout.app.intelligence.Action
import com.blackout.app.redact.RedactionEngine

/**
 * The redaction surface.
 *
 * Bars are drawn as a Compose overlay rather than by re-rendering the bitmap on every tap - a
 * 1536x2048 copy per tap would stutter. The overlay is purely what the user *sees*;
 * [RedactViewModel.renderRedacted] burns real pixels at export time, so what leaves the app is
 * never a view-layer illusion.
 */
@Composable
fun RedactScreen(
    viewModel: RedactViewModel,
    onRetake: () -> Unit,
    onShare: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val original = viewModel.original
    val image = remember(original) { original?.asImageBitmap() }

    // One haptic when a pass lands and the bars appear.
    LaunchedEffect(state.phase) {
        if (state.phase == Phase.READY && state.hiddenIds.isNotEmpty()) {
            Haptics.redactionApplied(context)
        }
    }

    Box(modifier.fillMaxSize().background(Color.Black)) {

        if (image != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(state.spans, state.imageWidth, state.imageHeight) {
                        detectTapGestures { offset ->
                            val t = FitTransform.of(
                                size.width.toFloat(), size.height.toFloat(),
                                state.imageWidth, state.imageHeight,
                            )
                            val point = t.toBitmap(offset.x, offset.y) ?: return@detectTapGestures
                            val slop = if (t.scale > 0f) (24 / t.scale).toInt() else 0
                            val hit = RedactionEngine.hitTest(
                                state.spans, point.first, point.second, slop,
                            ) ?: return@detectTapGestures
                            Haptics.tick(context)
                            viewModel.toggleSpan(hit.id)
                        }
                    }
            ) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                Canvas(Modifier.fillMaxSize()) {
                    val t = FitTransform.of(
                        size.width, size.height, state.imageWidth, state.imageHeight,
                    )
                    if (t.scale <= 0f) return@Canvas

                    for (span in state.spans) {
                        val decision = state.decisions[span.id] ?: continue
                        val hidden = span.id in state.hiddenIds
                        val pad = (span.rect.height * 0.12f).coerceAtLeast(3f)
                        val r = span.rect
                        val left = t.viewLeft(r) - pad * t.scale
                        val top = t.viewTop(r) - pad * t.scale
                        val w = t.viewWidth(r) + pad * 2 * t.scale
                        val h = t.viewHeight(r) + pad * 2 * t.scale

                        when {
                            hidden -> drawRect(Color.Black, Offset(left, top), Size(w, h))
                            decision.action == Action.UNSURE -> drawRect(
                                color = Color(0xFFFFC043),
                                topLeft = Offset(left, top),
                                size = Size(w, h),
                                alpha = 0.18f,
                            )
                            else -> Unit
                        }
                    }
                }
            }
        }

        HudChip(
            degraded = state.degraded,
            backend = state.analysis.stats.firstOrNull()?.backend,
            modifier = Modifier
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 16.dp, top = 8.dp),
        )

        OutlinedButton(
            onClick = viewModel::toggleDebug,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(end = 12.dp, top = 4.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) { Text("debug", fontSize = 12.sp) }

        if (state.showDebug) {
            DebugPanel(
                state = state,
                inventory = viewModel.modelInventory(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp, start = 12.dp, end = 12.dp),
            )
        }

        BottomBar(
            enabled = state.phase == Phase.READY,
            hideCount = state.hideCount,
            onRetake = onRetake,
            onShare = { viewModel.original?.let { onShare(it) } },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 18.dp),
        )

        if (state.phase == Phase.WORKING) {
            WorkingOverlay(state.statusLine)
        }
    }
}

@Composable
private fun HudChip(degraded: Boolean, backend: String?, modifier: Modifier = Modifier) {
    val label = when {
        degraded -> stringResource(R.string.hud_degraded)
        backend != null -> stringResource(R.string.hud_on_device) + " · $backend"
        else -> stringResource(R.string.hud_on_device)
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = if (degraded) MaterialTheme.colorScheme.error.copy(alpha = 0.22f)
        else Color.White.copy(alpha = 0.14f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_shield),
                contentDescription = null,
                tint = if (degraded) MaterialTheme.colorScheme.error else Color.White,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = Color.White,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun DebugPanel(
    state: RedactUiState,
    inventory: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.82f),
    ) {
        Column(Modifier.padding(12.dp)) {
            DebugLine("spans", "${state.spans.size}   ocr ${state.ocrMs}ms")
            DebugLine("verdict", "hide ${state.hideCount} · keep ${state.keepCount} · unsure ${state.unsureCount}")
            DebugLine("overrides", "${state.overrides.size} tap(s)")
            state.analysis.docSummary?.let { DebugLine("doctype", it) }
            for (stat in state.analysis.stats) {
                DebugLine(
                    stat.label,
                    "${stat.model} · ${stat.backend} · ${stat.elapsedMs}ms · " +
                        "${stat.batchCount} batch · ${stat.spanCount} spans",
                )
            }
            if (state.analysis.stats.isNotEmpty()) {
                DebugLine("inference", "${state.analysis.totalMs}ms total")
            }
            state.analysis.degradedReason?.let { DebugLine("degraded", it) }
            DebugLine("models", inventory)
        }
    }
}

@Composable
private fun DebugLine(key: String, value: String) {
    Row(Modifier.padding(vertical = 1.dp)) {
        Text(
            text = key.padEnd(10),
            color = Color(0xFF8B7CFF),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = value,
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun BottomBar(
    enabled: Boolean,
    hideCount: Int,
    onRetake: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            text = stringResource(R.string.tap_to_toggle),
            color = Color.White.copy(alpha = 0.55f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRetake,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_retake),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.retake))
            }
            Button(
                onClick = onShare,
                enabled = enabled,
                modifier = Modifier.weight(1f).height(54.dp),
                shape = MaterialTheme.shapes.large,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_share),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (hideCount > 0) stringResource(R.string.share_redacted)
                    else stringResource(R.string.share_image)
                )
            }
        }
    }
}

@Composable
private fun WorkingOverlay(status: String) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 3.dp,
                modifier = Modifier.size(38.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(status, color = Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.hud_on_device),
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
        }
    }
}
