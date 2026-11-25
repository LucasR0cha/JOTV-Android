package com.joabtv.app.ui.movies

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Melhorar imagem do player. O celular e a TV não têm o motor de shaders do computador, então aqui vale só o que a placa de vídeo
 * faz em uma passada por quadro, com custo previsível (nitidez, contraste, cores e brilho). Cada perfil guarda a própria escolha
 * e tudo nasce desligado, então quem nunca abre o painel toca o vídeo pelo mesmo caminho de sempre.
 */
internal enum class ImagePreset(val key: String, val label: String, val note: String) {
    OFF("off", "Desligado", "A imagem toca como vem do arquivo."),
    SHARP("sharp", "Nítida", "Realça os contornos sem mexer nas cores."),
    ANIME("anime", "Anime", "Contornos firmes e cores um pouco mais vivas."),
    MOVIE("movie", "Filme", "Contraste e nitidez moderados para filme e série."),
    VIVID("vivid", "Vivo", "Cores fortes, boas para tela de celular."),
    CUSTOM("custom", "Personalizado", "Os ajustes que você fez.");

    companion object {
        fun fromKey(key: String?): ImagePreset = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** Ampliador que roda antes dos ajustes. Só amplia quando o vídeo é menor que a tela, e desligado não entra no caminho do player. */
internal enum class ImageUpscaler(val key: String, val label: String, val note: String) {
    OFF("off", "Desligado", "A tela amplia o vídeo do jeito de sempre."),
    SGSR("sgsr", "SGSR", "Amplia o vídeo até a tela mantendo as bordas firmes. Ajuda em vídeo 480p e 720p.");

    companion object {
        fun fromKey(key: String?): ImageUpscaler = entries.firstOrNull { it.key == key } ?: OFF
    }
}

/** Trava de queda de quadros do Melhorar imagem. Só a cena de revisão do debug desliga, porque o emulador desenha em software e perde quadros sempre. */
internal object ImageEnhanceGuard {
    @Volatile
    var dropGuard: Boolean = true
}

private const val UPSCALE_MIN = 1.1f
private const val UPSCALE_MAX = 3f
/** Teto de pixels da saída do ampliador, perto de 1080p. O que passar disso a própria tela amplia, sem custo para a placa. */
private const val UPSCALE_MAX_PIXELS = 2_200_000f

/**
 * Tamanho de saída do Upscaler para um vídeo [inW]x[inH] numa superfície [targetW]x[targetH]. Devolve nulo quando não há o que
 * ampliar (vídeo já do tamanho da tela ou tamanho ainda desconhecido). Mantém a proporção, não passa de 3 vezes nem de uns 1080p.
 */
internal fun upscaleOutput(inW: Int, inH: Int, targetW: Int, targetH: Int): Pair<Int, Int>? {
    if (inW <= 0 || inH <= 0 || targetW <= 0 || targetH <= 0) return null
    val scale = minOf(targetW.toFloat() / inW, targetH.toFloat() / inH, UPSCALE_MAX, sqrt(UPSCALE_MAX_PIXELS / (inW.toFloat() * inH)))
    if (scale < UPSCALE_MIN) return null
    return Pair((inW * scale).roundToInt(), (inH * scale).roundToInt())
}

/** Degraus que o OK do controle anda na TV, onde não há barra para arrastar. Cada ajuste dá a volta no fim da lista. */
internal object ImageSteps {
    val SHARP = listOf(0, 25, 50, 75, 100)
    val CONTRAST = listOf(-20, -10, 0, 10, 20)
    val SATURATION = listOf(-30, -15, 0, 15, 30)
    val BRIGHTNESS = listOf(-10, -5, 0, 5, 10)

    /** O próximo degrau maior que [current]. Depois do último volta ao primeiro. */
    fun next(current: Int, steps: List<Int>): Int = steps.firstOrNull { it > current } ?: steps.first()
}

/** Valores já prontos para a placa de vídeo. 1 em contraste e cores é "sem mudança", 0 em nitidez e brilho também. */
internal data class ImageShaderParams(val sharp: Float, val contrast: Float, val saturation: Float, val brightness: Float) {
    companion object { val NONE = ImageShaderParams(0f, 1f, 1f, 0f) }
}

/**
 * A escolha do perfil. Os números são inteiros em porcentagem, para o painel andar de um em um e o texto guardado ficar curto.
 * [preset] é só o rótulo. Quem manda são os quatro ajustes, e [normalized] volta para um preset quando eles coincidem com um.
 */
internal data class ImageEnhance(
    val preset: ImagePreset = ImagePreset.OFF,
    /** 0 a 100. Zero desliga a nitidez. */
    val sharp: Int = 0,
    /** -30 a 30, em porcentagem sobre o contraste do arquivo. */
    val contrast: Int = 0,
    /** -50 a 50, em porcentagem sobre as cores do arquivo. */
    val saturation: Int = 0,
    /** -20 a 20. */
    val brightness: Int = 0,
    /** Ampliador de vídeo. Vale por conta própria e não faz parte das escolhas prontas. */
    val upscaler: ImageUpscaler = ImageUpscaler.OFF,
) {
    /** Algum dos quatro ajustes está fora do zero. */
    val filtersOn: Boolean get() = sharp != 0 || contrast != 0 || saturation != 0 || brightness != 0
    val isOn: Boolean get() = filtersOn || upscaler != ImageUpscaler.OFF

    fun shaderParams(): ImageShaderParams = if (!filtersOn) ImageShaderParams.NONE else ImageShaderParams(
        sharp = sharp.coerceIn(SHARP_RANGE) / 100f,
        contrast = 1f + contrast.coerceIn(CONTRAST_RANGE) / 100f,
        saturation = 1f + saturation.coerceIn(SATURATION_RANGE) / 100f,
        brightness = brightness.coerceIn(BRIGHTNESS_RANGE) / 100f,
    )

    /** Limita cada ajuste à faixa dele e acha o preset que combina. Tudo zerado é Desligado, e valor fora de preset é Personalizado. */
    fun normalized(): ImageEnhance {
        val fixed = copy(
            sharp = sharp.coerceIn(SHARP_RANGE), contrast = contrast.coerceIn(CONTRAST_RANGE),
            saturation = saturation.coerceIn(SATURATION_RANGE), brightness = brightness.coerceIn(BRIGHTNESS_RANGE),
        )
        if (!fixed.filtersOn) return if (upscaler == ImageUpscaler.OFF) OFF else ImageEnhance(upscaler = upscaler)
        val match = PRESETS.firstOrNull { it.sameValues(fixed) }
        return fixed.copy(preset = match?.preset ?: ImagePreset.CUSTOM)
    }

    private fun sameValues(o: ImageEnhance) = sharp == o.sharp && contrast == o.contrast && saturation == o.saturation && brightness == o.brightness

    fun withSharp(v: Int) = copy(sharp = v).normalized()
    fun withContrast(v: Int) = copy(contrast = v).normalized()
    fun withSaturation(v: Int) = copy(saturation = v).normalized()
    fun withBrightness(v: Int) = copy(brightness = v).normalized()
    fun withUpscaler(u: ImageUpscaler) = copy(upscaler = u).normalized()

    /** Troca a escolha pronta e mantém o ampliador. Desligado é o interruptor geral e apaga tudo, inclusive o ampliador. */
    fun withPreset(p: ImagePreset): ImageEnhance = if (p == ImagePreset.OFF) OFF else of(p).copy(upscaler = upscaler)

    /** Texto guardado no perfil. Vazio quer dizer tudo desligado, e sem ampliador o texto segue igual ao de antes (v1). */
    fun encode(): String = when {
        !isOn -> ""
        upscaler == ImageUpscaler.OFF -> "v1,${preset.key},$sharp,$contrast,$saturation,$brightness"
        else -> "v2,${preset.key},$sharp,$contrast,$saturation,$brightness,${upscaler.key}"
    }

    companion object {
        val SHARP_RANGE = 0..100
        val CONTRAST_RANGE = -30..30
        val SATURATION_RANGE = -50..50
        val BRIGHTNESS_RANGE = -20..20

        val OFF = ImageEnhance()
        val PRESETS: List<ImageEnhance> = listOf(
            ImageEnhance(ImagePreset.SHARP, sharp = 45),
            ImageEnhance(ImagePreset.ANIME, sharp = 65, contrast = 8, saturation = 12),
            ImageEnhance(ImagePreset.MOVIE, sharp = 30, contrast = 8, saturation = 4),
            ImageEnhance(ImagePreset.VIVID, sharp = 25, contrast = 10, saturation = 25),
        )

        fun of(preset: ImagePreset): ImageEnhance = PRESETS.firstOrNull { it.preset == preset } ?: OFF

        /** Lê o texto do perfil. Qualquer coisa que não for do formato vira Desligado, nunca derruba o player. */
        fun decode(raw: String?): ImageEnhance {
            val parts = raw?.trim()?.split(',') ?: return OFF
            val v2 = parts.size == 7 && parts[0] == "v2"
            if (!v2 && !(parts.size == 6 && parts[0] == "v1")) return OFF
            val n = parts.subList(2, 6).map { it.toIntOrNull() ?: return OFF }
            val up = if (v2) ImageUpscaler.fromKey(parts[6]) else ImageUpscaler.OFF
            return ImageEnhance(ImagePreset.fromKey(parts[1]), n[0], n[1], n[2], n[3], up).normalized()
        }
    }
}
