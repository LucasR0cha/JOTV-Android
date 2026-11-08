package com.joabtv.app.ui.common

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Esconde as barras do sistema (status + navegação) enquanto este Composable
 * estiver na composição — modo imersivo fullscreen para o player.
 * Ao sair (onDispose), restaura as barras.
 *
 * Uso: chamar dentro de uma tela de player fullscreen.
 */
@Composable
fun ImmersiveFullscreen() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            val controller = WindowInsetsControllerCompat(window, view)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (window != null) {
                val controller = WindowInsetsControllerCompat(window, view)
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }
}

/**
 * Mantém a tela do dispositivo ligada enquanto este Composable estiver na composição
 * (o player não emite eventos de toque, então o sistema desligaria a tela).
 * Ao sair (onDispose), libera o flag e a tela volta a desligar normalmente.
 */
@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Garante barras visíveis (telas normais de navegação). */
@Composable
fun ShowSystemBars() {
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        if (window != null) {
            WindowInsetsControllerCompat(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }
}
