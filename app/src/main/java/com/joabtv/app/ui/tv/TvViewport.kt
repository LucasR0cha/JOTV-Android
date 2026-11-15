package com.joabtv.app.ui.tv

import android.content.res.Configuration
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import kotlin.math.roundToInt

private val LocalTvViewportProvided = staticCompositionLocalOf { false }

internal fun tvViewportDensity(widthPx: Int, heightPx: Int): Float =
    minOf(widthPx / 960f, heightPx / 540f).coerceAtLeast(.1f)

/** Unifica a área de trabalho da TV sem depender dos dpi informados pelo fabricante. */
@Composable
internal fun TvViewport(content: @Composable () -> Unit) {
    if (LocalTvViewportProvided.current) {
        content()
        return
    }
    val config = LocalConfiguration.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = tvViewportDensity(constraints.maxWidth, constraints.maxHeight)
        val normalized = remember(config, constraints.maxWidth, constraints.maxHeight, density) {
            Configuration(config).apply {
                screenWidthDp = (constraints.maxWidth / density).roundToInt()
                screenHeightDp = (constraints.maxHeight / density).roundToInt()
                smallestScreenWidthDp = minOf(screenWidthDp, screenHeightDp)
                densityDpi = (density * 160).roundToInt()
                fontScale = 1f
            }
        }
        CompositionLocalProvider(
            LocalDensity provides Density(density, 1f),
            LocalConfiguration provides normalized,
            LocalTvViewportProvided provides true,
            content = content,
        )
    }
}
