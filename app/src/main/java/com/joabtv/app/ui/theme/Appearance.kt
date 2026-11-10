package com.joabtv.app.ui.theme

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.joabtv.app.data.local.AppTheme

internal data class AppPalette(
    val accent: Color,
    val soft: Color,
    val fill: Color,
    val dark: Color,
    val primary: Color,
    val primaryEnd: Color,
    val onPrimary: Color,
) {
    val primaryBrush: Brush get() = Brush.horizontalGradient(listOf(primary, primaryEnd))
}

internal fun paletteFor(theme: AppTheme): AppPalette = when (theme) {
    AppTheme.GRAPHITE -> AppPalette(Color(0xFFA7CAFF), Color(0xFF202C3C), Color(0xFF377BCE), Color(0xFF1565C0), Color(0xFFF5F5F5), Color(0xFFF5F5F5), Color(0xFF111111))
    AppTheme.OCEAN -> AppPalette(Color(0xFFA7CAFF), Color(0xFF1C2B40), Color(0xFF397FD7), Color(0xFF296EBC), Color(0xFF296EBC), Color(0xFF545BC0), Color.White)
    AppTheme.JADE -> AppPalette(Color(0xFF9DE0BF), Color(0xFF1B3026), Color(0xFF287C5E), Color(0xFF246E68), Color(0xFF287C5E), Color(0xFF246E68), Color.White)
}

internal val LocalAppPalette = staticCompositionLocalOf { paletteFor(AppTheme.GRAPHITE) }
val LocalReducedMotion = compositionLocalOf { false }

val PrimaryActionBrush: Brush @Composable @ReadOnlyComposable get() = LocalAppPalette.current.primaryBrush
val PrimaryActionText: Color @Composable @ReadOnlyComposable get() = LocalAppPalette.current.onPrimary

@Composable
internal fun systemReducedMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    fun read() = runCatching {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false)
    var reduced by remember(resolver) { mutableStateOf(read()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { reduced = read() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        reduced = read()
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return reduced
}
