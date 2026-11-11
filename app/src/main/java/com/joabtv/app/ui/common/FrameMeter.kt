package com.joabtv.app.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Medidor de quadros no canto da tela (Configurações, Diagnóstico). Atualiza a cada segundo com os quadros desenhados
 * por segundo, o quadro mais lento de cada dez, a fatia de quadros que passaram de 16 e de 33 ms e de onde vem o tempo.
 * "UI" é a thread da interface (animação, medida, layout e gravação do desenho), "render" é o envio para a placa de vídeo
 * (sincronia, comandos e troca de buffer) e "GPU" é o tempo da placa (só no Android 12 em diante).
 * Dá para ver na hora se o aparelho está preso na CPU ou na placa de vídeo, e quanto cada tela custa.
 */
@Composable
fun FrameMeterOverlay(modifier: Modifier = Modifier) {
    val view = LocalView.current
    var text by remember { mutableStateOf("medindo...") }
    DisposableEffect(view) {
        val window: Window? = view.context.findActivity()?.window
        if (window == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            text = "medidor indisponível neste Android"
            return@DisposableEffect onDispose {}
        }
        val thread = HandlerThread("jotv-meter").apply { start() }
        val main = Handler(Looper.getMainLooper())
        val totals = ArrayList<Long>(256)
        var slow16 = 0
        var slow33 = 0
        var ui = 0L
        var render = 0L
        var gpu = 0L
        var gpuFrames = 0
        var since = SystemClock.elapsedRealtimeNanos()
        val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val total = m.getMetric(FrameMetrics.TOTAL_DURATION)
            totals.add(total)
            if (total > 16_700_000L) slow16++
            if (total > 33_000_000L) slow33++
            ui += m.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) + m.getMetric(FrameMetrics.ANIMATION_DURATION) +
                m.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) + m.getMetric(FrameMetrics.DRAW_DURATION)
            render += m.getMetric(FrameMetrics.SYNC_DURATION) + m.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION) +
                m.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                gpu += m.getMetric(FrameMetrics.GPU_DURATION)
                gpuFrames++
            }
            val now = SystemClock.elapsedRealtimeNanos()
            if (now - since >= 1_000_000_000L && totals.isNotEmpty()) {
                val n = totals.size
                totals.sort()
                val p90 = totals[(n * 9 / 10).coerceAtMost(n - 1)] / 1e6
                val seconds = (now - since) / 1e9
                val line = String.format(
                    java.util.Locale.US, "%.0f quadros/s  p90 %.0f ms  >16 %d%%  >33 %d%%\nUI %.1f  render %.1f%s ms",
                    n / seconds, p90, slow16 * 100 / n, slow33 * 100 / n, ui / 1e6 / n, render / 1e6 / n,
                    if (gpuFrames > 0) String.format(java.util.Locale.US, "  GPU %.1f", gpu / 1e6 / gpuFrames) else "",
                )
                main.post { text = line }
                totals.clear(); slow16 = 0; slow33 = 0; ui = 0; render = 0; gpu = 0; gpuFrames = 0
                since = now
            }
        }
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        onDispose {
            runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
            thread.quitSafely()
        }
    }
    Text(
        text, color = Color.White, fontSize = 13.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace,
        modifier = modifier.background(Color(0xCC000000)).padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
