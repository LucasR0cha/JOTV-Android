package com.joabtv.app.ui.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.joabtv.app.ui.theme.LocalUiScale

/**
 * Telas de lista e formulário que já seguem o desenho do webOS (Configurações com resumo fixo à
 * direita, Addons, Explorar addons, Perfis, Busca e Descobrir) entram na TV na escala do webOS.
 * O celular mede em dp para o dedo. Aqui 1dp vira 1/1600 da largura da tela, a mesma proporção
 * do CSS do webOS (texto de 1.2rem, margem de 3rem), em vez do celular esticado.
 * A rolagem segue a regra do focus.js, com o item focado a 3rem da borda.
 */
@Composable
fun TvScaled(content: @Composable () -> Unit) {
    val d = LocalDensity.current
    val scale = tvScaledFactor()
    // Margem calculada antes da troca de escala, em rem da tela real.
    val inset = 3.r
    CompositionLocalProvider(LocalDensity provides Density(scale, d.fontScale)) {
        TvScrollArea(inset = with(d) { inset.toPx() }.let { px -> androidx.compose.ui.unit.Dp(px / scale) }) {
            TvText(content)
        }
    }
}

@Composable
private fun tvScaledFactor(): Float {
    val d = LocalDensity.current
    val config = LocalConfiguration.current
    return minOf(config.screenWidthDp * d.density / 1600f, config.screenHeightDp * d.density / 900f) * LocalUiScale.current
}

/**
 * Quanto falta para a margem larga das telas escaladas (pagePadding de 28) chegar em [tvPad].
 * Com o menu Superior ou Oculto não há faixa do menu, e Buscar, Addons e Configurações
 * alinham com a barra e com as outras telas (margem do webOS).
 */
val tvScaledPadGap: androidx.compose.ui.unit.Dp
    @Composable get() = (tvPad - (28f * tvScaledFactor() / LocalDensity.current.density).dp).coerceAtLeast(0.dp)
