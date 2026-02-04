package com.anurag.cctvprimary.ui.responsive

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Renders a fixed "reference design" canvas and scales it uniformly to fit the device display.
 *
 * Intent:
 * - Keep the existing single-screen layout structure stable across devices/aspect ratios.
 * - Scale up on larger screens (up to maxScale).
 * - Scale down on smaller screens (down to minScale).
 * - React automatically to Android "Display size" changes (density changes -> recomposition).
 *
 * IMPORTANT:
 * - This should not change any functional behavior; it only applies size/scaling concerns.
 * - Insets are accounted for when computing scale so content stays within visible bounds.
 */
@Composable
fun ResponsiveSingleScreen(
    modifier: Modifier = Modifier,
    refWidth: Dp = 360.dp,
    // Default refWidth and refHeight
    refHeight: Dp = 800.dp,
    // Default minScale and maxScale.
    minScale: Float = 0.85f,
    maxScale: Float = 1.30f,
    // Optional: clamp fontScale to protect the fixed reference layout from overflow/wrapping.
    // Set to null to preserve full accessibility font scaling.
    safeFontScaleRange: ClosedFloatingPointRange<Float>? = 0.90f..1.10f,
    logTag: String = "CCTV_PRIMARY",
    content: @Composable () -> Unit
) {
    val config = LocalConfiguration.current
    val baseDensity = LocalDensity.current


    // Compute a density provider that optionally clamps fontScale (to avoid layout blow-ups).
    val providedDensity: Density = remember(baseDensity.density, config.fontScale, safeFontScaleRange) {
        if (safeFontScaleRange == null) {
            baseDensity
        } else {
            val safeFontScale = config.fontScale.coerceIn(safeFontScaleRange.start, safeFontScaleRange.endInclusive)
            Density(density = baseDensity.density, fontScale = safeFontScale)
        }
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Using full screen dimensions
        val usableW = maxWidth.value
        val usableH = maxHeight.value

        val scaleXFactor = usableW / refWidth.value
        val scaleYFactor = usableH / refHeight.value
        val rawScale = minOf(scaleXFactor, scaleYFactor)
        val finalScale = rawScale.coerceIn(minScale, maxScale)

        LaunchedEffect(maxWidth, maxHeight, refWidth, refHeight, finalScale, config.fontScale) {
            Log.d(
                logTag,
                "🔵 [RESPONSIVE_UI] ref=${refWidth.value}x${refHeight.value}dp usable=${usableW}x${usableH}dp " +
                    "rawScale=$rawScale finalScale=$finalScale density=${baseDensity.density} fontScale=${config.fontScale} "
            )
        }

        CompositionLocalProvider(LocalDensity provides providedDensity) {
            Box(
                modifier = Modifier
                    .size(refWidth, refHeight)
                    .graphicsLayer {
                        this.scaleX = finalScale
                        this.scaleY = finalScale
                    }
            ) {

                    content()

            }
        }
    }
}

