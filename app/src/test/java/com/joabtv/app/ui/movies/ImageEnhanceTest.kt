package com.joabtv.app.ui.movies

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEnhanceTest {
    @Test
    fun nasceDesligadoENaoMandaNadaParaAPlaca() {
        val off = ImageEnhance()
        assertFalse(off.isOn)
        assertEquals("", off.encode())
        assertEquals(ImageShaderParams.NONE, off.shaderParams())
        assertEquals(ImageEnhance.OFF, ImageEnhance.decode(""))
        assertEquals(ImageEnhance.OFF, ImageEnhance.decode(null))
    }

    @Test
    fun presetViraNumerosDaPlaca() {
        val p = ImageEnhance.of(ImagePreset.ANIME).shaderParams()
        assertEquals(0.65f, p.sharp, 1e-6f)
        assertEquals(1.08f, p.contrast, 1e-6f)
        assertEquals(1.12f, p.saturation, 1e-6f)
        assertEquals(0f, p.brightness, 1e-6f)
        val neutral = ImageEnhance(sharp = 40).shaderParams()
        assertEquals(1f, neutral.contrast, 1e-6f)
        assertEquals(1f, neutral.saturation, 1e-6f)
    }

    @Test
    fun ajusteAMaoViraPersonalizadoEVoltaAoPresetQuandoCoincide() {
        val base = ImageEnhance.of(ImagePreset.SHARP)
        val moved = base.withSharp(50)
        assertEquals(ImagePreset.CUSTOM, moved.preset)
        assertTrue(moved.isOn)
        assertEquals(ImagePreset.SHARP, moved.withSharp(45).preset)
        assertEquals(ImageEnhance.of(ImagePreset.SHARP), moved.withSharp(45))
    }

    @Test
    fun tudoZeradoVoltaAoDesligado() {
        val custom = ImageEnhance(sharp = 30, contrast = 5)
        assertEquals(ImagePreset.CUSTOM, custom.normalized().preset)
        val zero = custom.withSharp(0).withContrast(0)
        assertEquals(ImageEnhance.OFF, zero)
        assertEquals(ImagePreset.OFF, zero.preset)
        assertEquals("", zero.encode())
    }

    @Test
    fun cadaAjusteFicaNaFaixaDele() {
        val big = ImageEnhance(sharp = 999, contrast = 99, saturation = -99, brightness = 99).normalized()
        assertEquals(100, big.sharp)
        assertEquals(30, big.contrast)
        assertEquals(-50, big.saturation)
        assertEquals(20, big.brightness)
        val low = ImageEnhance(sharp = -5).normalized()
        assertEquals(ImageEnhance.OFF, low)
    }

    @Test
    fun textoGuardadoVoltaIgualParaTodosOsPresetsEParaAjusteAMao() {
        for (preset in ImageEnhance.PRESETS) assertEquals(preset, ImageEnhance.decode(preset.encode()))
        val custom = ImageEnhance(sharp = 33, contrast = -7, saturation = 18, brightness = 4).normalized()
        assertEquals(ImagePreset.CUSTOM, custom.preset)
        assertEquals(custom, ImageEnhance.decode(custom.encode()))
    }

    @Test
    fun textoEstranhoViraDesligadoEnuncaDerrubaOPlayer() {
        for (raw in listOf("lixo", "v2,sharp,45,0,0,0", "v1,sharp,45,0,0", "v1,sharp,45,0,0,0,9", "v1,sharp,a,0,0,0", "v1,,,,,", ",,,,,", "v1")) {
            assertEquals(raw, ImageEnhance.OFF, ImageEnhance.decode(raw))
        }
        // Nome de preset desconhecido não importa. Valem os números, e o rótulo volta para o preset que combina com eles.
        val unknown = ImageEnhance.decode("v1,nada,45,0,0,0")
        assertEquals(45, unknown.sharp)
        assertEquals(ImagePreset.SHARP, unknown.preset)
    }

    @Test
    fun degrauDaTvAndaEDaAVolta() {
        assertEquals(25, ImageSteps.next(0, ImageSteps.SHARP))
        // Um valor de barra do celular (33) cai no próximo degrau da TV.
        assertEquals(50, ImageSteps.next(33, ImageSteps.SHARP))
        assertEquals(0, ImageSteps.next(100, ImageSteps.SHARP))
        assertEquals(-10, ImageSteps.next(-20, ImageSteps.CONTRAST))
        assertEquals(-20, ImageSteps.next(20, ImageSteps.CONTRAST))
        assertEquals(0, ImageSteps.next(-5, ImageSteps.SATURATION))
        assertEquals(-10, ImageSteps.next(10, ImageSteps.BRIGHTNESS))
    }

    @Test
    fun osQuatroPresetsTemNumerosDiferentes() {
        val all = ImageEnhance.PRESETS.map { Triple(it.sharp, it.contrast, it.saturation) }
        assertEquals(all.size, all.toSet().size)
        assertTrue(ImageEnhance.PRESETS.all { it.isOn && it.preset != ImagePreset.OFF && it.preset != ImagePreset.CUSTOM })
    }

    @Test
    fun upscalerSozinhoLigaMesmoComOsAjustesZerados() {
        val up = ImageEnhance(upscaler = ImageUpscaler.SGSR).normalized()
        assertTrue(up.isOn)
        assertFalse(up.filtersOn)
        assertEquals(ImagePreset.OFF, up.preset)
        assertEquals(ImageShaderParams.NONE, up.shaderParams())
        assertEquals("v2,off,0,0,0,0,sgsr", up.encode())
        assertEquals(up, ImageEnhance.decode(up.encode()))
        // Desligar o Upscaler com tudo zerado volta ao Desligado e ao texto vazio.
        val off = up.withUpscaler(ImageUpscaler.OFF)
        assertEquals(ImageEnhance.OFF, off)
        assertEquals("", off.encode())
    }

    @Test
    fun textoAntigoContinuaLidoEQuemNaoUsaUpscalerGuardaOMesmoTextoDeAntes() {
        val old = ImageEnhance.decode("v1,sharp,45,0,0,0")
        assertEquals(ImageEnhance.of(ImagePreset.SHARP), old)
        assertEquals(ImageUpscaler.OFF, old.upscaler)
        assertEquals("v1,sharp,45,0,0,0", old.encode())
        val both = ImageEnhance.of(ImagePreset.ANIME).withUpscaler(ImageUpscaler.SGSR)
        assertEquals("v2,anime,65,8,12,0,sgsr", both.encode())
        assertEquals(both, ImageEnhance.decode(both.encode()))
        // Upscaler de nome desconhecido não derruba. Vira desligado e os números valem.
        assertEquals(ImageUpscaler.OFF, ImageEnhance.decode("v2,sharp,45,0,0,0,nada").upscaler)
    }

    @Test
    fun escolhaProntaMantemOUpscalerEDesligadoApagaTudo() {
        val on = ImageEnhance(upscaler = ImageUpscaler.SGSR)
        val anime = on.withPreset(ImagePreset.ANIME)
        assertEquals(ImagePreset.ANIME, anime.preset)
        assertEquals(ImageUpscaler.SGSR, anime.upscaler)
        assertEquals(ImageUpscaler.SGSR, anime.withSharp(10).upscaler)
        assertEquals(ImageEnhance.OFF, anime.withPreset(ImagePreset.OFF))
    }

    @Test
    fun upscalerSoAmpliaQuandoATelaForMaiorENuncaPassaDoTeto() {
        // 480p na tela de 1080p amplia até a tela.
        assertEquals(Pair(1920, 1079), upscaleOutput(854, 480, 1920, 1080))
        assertEquals(Pair(1920, 1080), upscaleOutput(1280, 720, 1920, 1080))
        // Vídeo do tamanho da tela, ou quase, não amplia.
        assertNull(upscaleOutput(1920, 1080, 1920, 1080))
        assertNull(upscaleOutput(1000, 562, 1080, 608))
        // Tamanho desconhecido não amplia e não derruba.
        assertNull(upscaleOutput(854, 480, -1, -1))
        assertNull(upscaleOutput(0, 0, 1920, 1080))
        // Tela de 4K não leva a saída além de uns 1080p. O resto a própria tela amplia.
        val big = upscaleOutput(854, 480, 3840, 2160)!!
        assertTrue(big.first * big.second <= 2_300_000)
        assertEquals(Pair(1920, 1080), upscaleOutput(640, 360, 3840, 2160))
        // Vídeo bem pequeno não passa de 3 vezes.
        assertEquals(Pair(960, 540), upscaleOutput(320, 180, 3840, 2160))
    }
}
