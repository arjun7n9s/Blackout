package com.blackout.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.blackout.app.R
import com.blackout.app.ui.theme.BlackoutInk
import com.blackout.app.ui.theme.BlackoutTheme

@Composable
fun HomeScreen(
    permission: CameraPermissionState,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 24.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))

        BrandMark()

        Spacer(Modifier.height(28.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tagline),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.home_blurb),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )

        Spacer(Modifier.weight(1f))

        if (permission.denied || permission.deniedPermanently) {
            PermissionNotice(
                permanent = permission.deniedPermanently,
                onRetry = permission.request,
            )
            Spacer(Modifier.height(16.dp))
        }

        Button(
            onClick = onTakePhoto,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp),
            shape = MaterialTheme.shapes.large,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_camera),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.take_photo),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(12.dp))

        OutlinedButton(
            onClick = onPickFromGallery,
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            shape = MaterialTheme.shapes.large,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.onBackground,
            ),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_image),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.pick_from_gallery),
                style = MaterialTheme.typography.labelLarge,
            )
        }

        Spacer(Modifier.height(20.dp))

        Text(
            text = stringResource(R.string.v0_notice),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The launcher mark redrawn in Compose: two lines of text, one of them redacted. */
@Composable
private fun BrandMark(modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.onBackground
    val barColor = BlackoutInk
    Surface(
        modifier = modifier.size(112.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            fun stripe(xFrac: Float, yFrac: Float, wFrac: Float, hFrac: Float, color: Color, alpha: Float) {
                drawRoundRect(
                    color = color,
                    alpha = alpha,
                    topLeft = Offset(w * xFrac, h * yFrac),
                    size = Size(w * wFrac, h * hFrac),
                    cornerRadius = CornerRadius(h * hFrac / 2f),
                )
            }
            stripe(0.24f, 0.31f, 0.36f, 0.07f, lineColor, 0.93f)
            stripe(0.20f, 0.45f, 0.56f, 0.13f, barColor, 1f)
            stripe(0.24f, 0.66f, 0.26f, 0.07f, lineColor, 0.93f)
        }
    }
}

@Composable
private fun PermissionNotice(
    permanent: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.perm_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(
                    if (permanent) R.string.perm_denied_permanently else R.string.perm_rationale
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRetry) {
                    Text(
                        stringResource(
                            if (permanent) R.string.perm_open_settings else R.string.perm_grant
                        )
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF08080C, heightDp = 780)
@Composable
private fun HomeScreenPreview() {
    BlackoutTheme {
        HomeScreen(
            permission = CameraPermissionState(
                granted = false,
                denied = true,
                deniedPermanently = false,
                request = {},
                openSettings = {},
            ),
            onTakePhoto = {},
            onPickFromGallery = {},
        )
    }
}
