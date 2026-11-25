package com.joabtv.app.ui.movies

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joabtv.app.ui.common.SidePanel
import com.joabtv.app.ui.theme.*
import kotlin.math.roundToInt

/**
 * Painel de Melhorar imagem do player no celular. Escolhas prontas em cima, o Upscaler no meio e os quatro ajustes embaixo, com o
 * resultado na hora. Entra pela direita e deixa a imagem à vista, porque é nela que se vê o que mudou. Cada ajuste é uma linha só
 * (nome, barra e valor), para tudo caber sem rolar no celular deitado. [onChange] vale para a pessoa ver (arrastando ou tocando) e
 * [onCommit] grava no perfil, sem escrever a cada pixel que o dedo anda. [upscaleStatus] diz o que o Upscaler faz com este vídeo.
 */
@Composable
internal fun PlayerImagePanel(
    config: ImageEnhance,
    hdr: Boolean,
    upscaleStatus: String,
    onChange: (ImageEnhance) -> Unit,
    onCommit: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    // O aviso do fim só aparece com altura de sobra. No celular deitado cada linha conta.
    val roomy = LocalConfiguration.current.screenHeightDp >= 480
    SidePanel(
        title = "Melhorar imagem", onDismiss = onClose, width = 400.dp, lightScrim = true,
        // A linha de baixo do título só aparece no HDR. No celular deitado cada linha conta, e as barras precisam caber.
        sub = if (hdr) "Vídeo HDR. A melhoria fica desligada nele para não estragar as cores." else null,
        subColor = Accent,
    ) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf(ImagePreset.OFF) + ImageEnhance.PRESETS.map { it.preset }).forEach { preset ->
                    PresetPill(preset.label, selected = config.preset == preset, enabled = !hdr) {
                        // A escolha pronta mantém o Upscaler. Desligado é o interruptor geral e apaga tudo.
                        onChange(config.withPreset(preset)); onCommit()
                    }
                }
                if (config.preset == ImagePreset.CUSTOM) PresetPill(ImagePreset.CUSTOM.label, selected = true, enabled = false) {}
            }
            Row(Modifier.fillMaxWidth().alpha(if (hdr) .45f else 1f), verticalAlignment = Alignment.CenterVertically) {
                Text("Upscaler", color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter, maxLines = 1,
                    modifier = Modifier.width(78.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImageUpscaler.entries.forEach { up ->
                        PresetPill(up.label, selected = config.upscaler == up, enabled = !hdr) { onChange(config.withUpscaler(up)); onCommit() }
                    }
                    // O que o Upscaler faz com este vídeo, ao lado da escolha.
                    if (!hdr && upscaleStatus.isNotEmpty()) Text(
                        upscaleStatus, color = TextTertiary, fontSize = 12.sp, fontFamily = Inter, maxLines = 1,
                        modifier = Modifier.align(Alignment.CenterVertically).padding(start = 2.dp),
                    )
                }
            }
            Column(Modifier.alpha(if (hdr) .45f else 1f)) {
                AdjustSlider("Nitidez", config.sharp, ImageEnhance.SHARP_RANGE, suffix = "%", signed = false, enabled = !hdr, onCommit = onCommit) { onChange(config.withSharp(it)) }
                AdjustSlider("Contraste", config.contrast, ImageEnhance.CONTRAST_RANGE, enabled = !hdr, onCommit = onCommit) { onChange(config.withContrast(it)) }
                AdjustSlider("Cores", config.saturation, ImageEnhance.SATURATION_RANGE, enabled = !hdr, onCommit = onCommit) { onChange(config.withSaturation(it)) }
                AdjustSlider("Brilho", config.brightness, ImageEnhance.BRIGHTNESS_RANGE, enabled = !hdr, onCommit = onCommit) { onChange(config.withBrightness(it)) }
            }
            if (roomy) Text(
                config.preset.note + (if (config.upscaler != ImageUpscaler.OFF) " " + config.upscaler.note else "") +
                    " Usa a placa de vídeo do aparelho. Se o vídeo começar a falhar, escolha Desligado.",
                color = TextTertiary, fontSize = 12.sp, lineHeight = 17.sp, fontFamily = Inter,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/** Pílula de escolha pronta. Marcada em azul, nunca menor que 48dp de altura. */
@Composable
private fun PresetPill(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Inter, maxLines = 1,
        modifier = Modifier.alpha(if (enabled || selected) 1f else .5f).heightIn(min = 48.dp).clip(RoundedCornerShape(999.dp))
            .background(if (selected) AccentFill else Color.White.copy(alpha = .14f))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    )
}

/** Um ajuste em uma linha, nome à esquerda, barra no meio e valor à direita. A barra tem 48dp de toque. */
@Composable
private fun AdjustSlider(
    label: String, value: Int, range: IntRange, enabled: Boolean, onCommit: () -> Unit,
    suffix: String = "", signed: Boolean = true, onValue: (Int) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter, maxLines = 1,
            modifier = Modifier.width(78.dp))
        Slider(
            value = value.toFloat(), onValueChange = { onValue(it.roundToInt()) }, onValueChangeFinished = onCommit,
            valueRange = range.first.toFloat()..range.last.toFloat(), enabled = enabled,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Accent, inactiveTrackColor = Color.White.copy(alpha = .22f)),
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(
            (if (signed && value > 0) "+$value" else "$value") + suffix, textAlign = TextAlign.End,
            color = if (value != 0) Accent else TextTertiary, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = Inter, maxLines = 1,
            modifier = Modifier.width(48.dp),
        )
    }
}
