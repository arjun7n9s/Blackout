package com.blackout.app.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import com.blackout.app.ocr.SkewMetrics
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blackout.app.R
import com.blackout.app.share.ShareGuard
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
    var pendingShareWarning by remember { mutableStateOf<ShareGuard.Warning?>(null) }
    var trayExpanded by remember { mutableStateOf(false) }

    // Zoom lives here rather than in the ViewModel: it is how the user is *looking* at the page,
    // not part of the redaction result, and it should not survive a new capture.
    var zoom by remember { mutableStateOf(1f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(original) {
        zoom = 1f
        panOffset = Offset.Zero
    }

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
                    .onSizeChanged { viewportSize = it }
                    // Pinch on the viewport, in screen coordinates. Deliberately *outside* the
                    // zoomed layer: gesture deltas reported inside it would already be divided by
                    // the current scale, so zooming would accelerate as you zoomed.
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, gestureZoom, _ ->
                            val next = (zoom * gestureZoom).coerceIn(1f, MAX_ZOOM)
                            // Panning a page that fits the screen just slides it off. Only a
                            // zoomed-in page has anywhere to go.
                            panOffset = if (next > 1f) {
                                clampPan(panOffset + pan, next, viewportSize)
                            } else {
                                Offset.Zero
                            }
                            zoom = next
                        }
                    }
                    .pointerInput(state.spans, state.imageWidth, state.imageHeight, state.mode) {
                        detectTapGestures { tap ->
                            val t = FitTransform.of(
                                size.width.toFloat(), size.height.toFloat(),
                                state.imageWidth, state.imageHeight,
                            )
                            // Undo the zoom before asking which span was hit. The layer scales
                            // about its centre, so this is the exact inverse of what graphicsLayer
                            // draws - without it every tap past 1x lands on the wrong line.
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val content = Offset(
                                cx + (tap.x - cx - panOffset.x) / zoom,
                                cy + (tap.y - cy - panOffset.y) / zoom,
                            )
                            val point = t.toBitmap(content.x, content.y)
                                ?: return@detectTapGestures
                            // The touch target shrinks on screen as you zoom in, so the slop has
                            // to shrink with it or a zoomed tap grabs a neighbouring line.
                            val slop = if (t.scale > 0f) (24 / (t.scale * zoom)).toInt() else 0
                            val hit = RedactionEngine.hitTest(
                                state.spans, point.first, point.second, slop,
                            ) ?: return@detectTapGestures
                            Haptics.tick(context)
                            when (state.mode) {
                                RedactMode.BLACKOUT -> viewModel.toggleSpan(hit.id)
                                RedactMode.COPY -> viewModel.copySpan(hit.id)
                            }
                        }
                    }
            ) {
                // Image and overlay share one layer, so the bars cannot drift off the text while
                // zooming - they are scaled by the same transform, not re-derived from it.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = zoom
                            scaleY = zoom
                            translationX = panOffset.x
                            translationY = panOffset.y
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

                    // Copy mode shows the page as it is - painting a bar over a line the user is
                    // trying to read off would defeat the mode. A faint wash marks what is
                    // tappable. Nothing in this branch can reach the export path.
                    if (state.mode == RedactMode.COPY) {
                        for (span in state.spans) {
                            val r = span.rect
                            drawRect(
                                color = Color.White,
                                topLeft = Offset(t.viewLeft(r), t.viewTop(r)),
                                size = Size(t.viewWidth(r), t.viewHeight(r)),
                                alpha = 0.10f,
                            )
                        }
                        return@Canvas
                    }

                    for (span in state.spans) {
                        val decision = state.decisions[span.id] ?: continue
                        val hidden = span.id in state.hiddenIds
                        val pad = (span.rect.height * 0.12f).coerceAtLeast(3f)
                        val r = span.rect
                        val left = t.viewLeft(r) - pad * t.scale
                        val top = t.viewTop(r) - pad * t.scale
                        val w = t.viewWidth(r) + pad * 2 * t.scale
                        val h = t.viewHeight(r) + pad * 2 * t.scale

                        // A slanted line is painted as its true oriented quad, exactly as
                        // RedactionEngine will burn it. The preview is the user's only evidence
                        // of what leaves the app, so the two must not disagree.
                        // The quad's own angle, not span.angleDeg - after Deskew.mapBack the field
                        // describes the straightened page and the quad describes the photograph.
                        val quad = span.quad
                            ?.takeIf { SkewMetrics.deviationFromHorizontal(it.angleDeg) >= 3f }
                            ?.inflate(pad)
                        if (quad != null && hidden) {
                            drawPath(
                                Path().apply {
                                    val pts = quad.points
                                    moveTo(
                                        pts[0].x * t.scale + t.offsetX,
                                        pts[0].y * t.scale + t.offsetY,
                                    )
                                    for (i in 1 until pts.size) {
                                        lineTo(
                                            pts[i].x * t.scale + t.offsetX,
                                            pts[i].y * t.scale + t.offsetY,
                                        )
                                    }
                                    close()
                                },
                                Color.Black,
                            )
                            continue
                        }

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
        }

        // HUD and persona share the top edge, so they share one Row. Positioning both absolutely
        // collided: the HUD line grows with the backend report and ran straight under the chip.
        Row(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudChip(
                degraded = state.degraded,
                backend = state.analysis.backendReport.chip().ifBlank { state.analysis.hudBackend },
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            // The persona chip belongs here and is deliberately not mounted yet - see
            // [PersonaSheet]. Nothing personas-related is reachable on the phone.
            OutlinedButton(
                onClick = viewModel::toggleDebug,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("debug", fontSize = 12.sp) }
        }

        if (state.showDebug) {
            DebugPanel(
                state = state,
                inventory = viewModel.modelInventory(),
                npuGate = viewModel.npuGate(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 56.dp, start = 12.dp, end = 12.dp),
            )
        }

        // One column owns the bottom edge: copy confirmation, mode tray, then the actions. Two
        // separately aligned BottomCenter children would sit on top of each other.
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.phase == Phase.READY) {
                state.lastCopied?.let { copied ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = Color(0xFFFFC043),
                        modifier = Modifier.padding(bottom = 10.dp),
                    ) {
                        Text(
                            stringResource(R.string.copied_value, copied.take(28)),
                            color = Color.Black,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
                ModeTray(
                    mode = state.mode,
                    expanded = trayExpanded,
                    onExpandedChange = { trayExpanded = it },
                    onModeChange = viewModel::setMode,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }

            BottomBar(
                enabled = state.phase == Phase.READY,
                hideCount = state.hideCount,
                copyMode = state.mode == RedactMode.COPY,
                onRetake = onRetake,
                onShare = {
                    // C-005: a motion-blurred statement produced one span, zero bars, and a
                    // normal share button. Make the user look at that before it leaves the app.
                    val warning = state.shareWarning
                    if (warning != null) pendingShareWarning = warning
                    else viewModel.original?.let { onShare(it) }
                },
            )
        }

        if (state.phase == Phase.WORKING) {
            WorkingOverlay(state.statusLine)
        }
    }

    pendingShareWarning?.let { warning ->
        AlertDialog(
            onDismissRequest = { pendingShareWarning = null },
            title = {
                Text(
                    stringResource(
                        when (warning.reason) {
                            ShareGuard.Reason.SKEWED -> R.string.share_guard_skew_title
                            ShareGuard.Reason.SPARSE_TEXT -> R.string.share_guard_title
                        }
                    )
                )
            },
            text = { Text(warning.message) },
            confirmButton = {
                TextButton(onClick = {
                    pendingShareWarning = null
                    viewModel.original?.let { onShare(it) }
                }) { Text(stringResource(R.string.share_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingShareWarning = null }) {
                    Text(stringResource(R.string.share_guard_back))
                }
            },
        )
    }
}

/** Far enough to read a mangled Aadhaar digit; beyond this it is all resampling artefacts. */
private const val MAX_ZOOM = 6f

/**
 * Keeps a zoomed page from being dragged off the screen.
 *
 * At scale `s` the content overhangs the viewport by `(s - 1) / 2` in each direction, and that
 * overhang is exactly how far it may travel before an edge pulls into view.
 */
private fun clampPan(pan: Offset, scale: Float, viewport: IntSize): Offset {
    if (viewport.width == 0 || viewport.height == 0) return pan
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
}

/**
 * The mode tray: one row collapsed, with a line of explanation per mode when expanded.
 *
 * Modelled on a phone camera's mode strip, minus the thing that strip usually carries. There is
 * no Photo/Video pair here because there is no second capture type - Blackout and Copy are two
 * ways of reading one still, not two things to record.
 */
@Composable
private fun ModeTray(
    mode: RedactMode,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onModeChange: (RedactMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Color.Black.copy(alpha = 0.72f),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectVerticalDragGestures { _, dragAmount ->
                        if (dragAmount < -6f) onExpandedChange(true)
                        if (dragAmount > 6f) onExpandedChange(false)
                    }
                }
                .padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Grab handle. Tappable as well as draggable - a 4dp strip is not a touch target, so
            // the tap area is padded well beyond the mark that is drawn.
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 40.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color.White.copy(alpha = 0.35f))
                        .size(width = 36.dp, height = 4.dp)
                )
            }

            if (expanded) {
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (entry in RedactMode.entries) {
                        ModeButton(
                            mode = entry,
                            selected = entry == mode,
                            onClick = { onModeChange(entry) },
                        )
                    }
                }
            } else {
                // Collapsed still has to answer "which mode am I in" - the gesture on the image
                // means different things, and a tray that hides that is a trap.
                Text(
                    text = stringResource(
                        when (mode) {
                            RedactMode.BLACKOUT -> R.string.mode_blackout
                            RedactMode.COPY -> R.string.mode_copy
                        }
                    ),
                    color = Color(0xFFFFC043),
                    fontSize = 12.sp,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

/** Icon over label, the shape a camera mode strip uses. */
@Composable
private fun ModeButton(
    mode: RedactMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val tint = if (selected) Color(0xFFFFC043) else Color.White.copy(alpha = 0.6f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(50))
                .background(
                    if (selected) Color.White.copy(alpha = 0.12f) else Color.Transparent
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(
                    when (mode) {
                        RedactMode.BLACKOUT -> R.drawable.ic_mode_blackout
                        RedactMode.COPY -> R.drawable.ic_mode_copy
                    }
                ),
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = stringResource(
                when (mode) {
                    RedactMode.BLACKOUT -> R.string.mode_blackout
                    RedactMode.COPY -> R.string.mode_copy
                }
            ),
            color = tint,
            fontSize = 10.sp,
        )
    }
}

/**
 * Who the document is going to.
 *
 * **Staged, not mounted.** Nothing in the app opens this yet, by request - it is kept built and
 * compiling so it can be switched on in one place once the prompt packs that would act on it
 * exist. Until then there is no persona affordance on the phone at all, which is the honest
 * state: selecting an audience that changes no verdict would imply a protection that is not
 * running.
 */
@Suppress("unused")
@Composable
private fun PersonaSheet(
    selected: Persona,
    onPick: (Persona) -> Unit,
    onDismiss: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = Color(0xFF16161A),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(stringResource(R.string.persona_title), color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(2.dp))
            Text(
                stringResource(R.string.persona_not_wired),
                color = Color(0xFFFFC043),
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(14.dp))
            for (persona in Persona.entries) {
                val isOn = persona == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPick(persona) }
                        .background(
                            if (isOn) Color.White.copy(alpha = 0.08f) else Color.Transparent
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            persona.label,
                            color = if (isOn) Color(0xFFFFC043) else Color.White,
                            fontSize = 14.sp,
                        )
                        Text(
                            persona.blurb,
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 11.sp,
                        )
                    }
                    if (isOn) {
                        Text(
                            stringResource(R.string.persona_selected_mark),
                            color = Color(0xFFFFC043),
                            fontSize = 15.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.persona_done), color = Color.White)
            }
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
    npuGate: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Black.copy(alpha = 0.82f),
    ) {
        Column(Modifier.padding(12.dp)) {
            DebugLine("spans", "${state.spans.size}   ocr ${state.ocrMs}ms")
            if (state.deskewDeg != 0f) {
                DebugLine("deskew", "straightened ${"%.1f".format(state.deskewDeg)}°")
            }
            DebugLine("hybrid", state.analysis.backendReport.hudLine())
            DebugLine("verdict", "hide ${state.hideCount} · keep ${state.keepCount} · unsure ${state.unsureCount}")
            DebugLine("overrides", "${state.overrides.size} tap(s)")
            state.analysis.docSummary?.let { DebugLine("doctype", it) }
            state.analysis.refereeSkipReason?.let { DebugLine("no-gemma", it) }
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
            DebugLine("npu", npuGate)
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
    copyMode: Boolean = false,
    onRetake: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            // The gesture does something different per mode, so the hint has to follow it.
            text = stringResource(
                if (copyMode) R.string.tap_to_copy else R.string.tap_to_toggle
            ),
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
