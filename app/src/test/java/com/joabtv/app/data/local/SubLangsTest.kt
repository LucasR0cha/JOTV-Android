package com.joabtv.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubLangsTest {
    @Test
    fun reconheceCodigosDeFontesDiferentes() {
        assertEquals("pob", SubLangs.of("pt-BR")?.key)
        assertEquals("pob", SubLangs.of("pob")?.key)
        assertEquals("por", SubLangs.of("pt")?.key)
        assertEquals("por", SubLangs.of("pt-AO")?.key)
        assertEquals("fre", SubLangs.of("fra")?.key)
        assertEquals("spl", SubLangs.of("es_419")?.key)
        assertEquals("chi", SubLangs.of("zh-Hant-TW")?.key)
        assertNull(SubLangs.of("und"))
        assertNull(SubLangs.of(""))
        assertEquals("Inglês", SubLangs.label("eng"))
        assertEquals("XHO", SubLangs.label("xho"))
        assertEquals("Legenda", SubLangs.label(null))
    }

    @Test
    fun ordenaPeloIdiomaEscolhido() {
        val langs = listOf("eng", "jpn", "por", "pob", "xho")
        assertEquals(listOf("pob", "por", "eng", "jpn", "xho"), langs.sortedBy { SubLangs.rank(it, "pob") })
        assertEquals(listOf("eng", "pob", "por", "jpn", "xho"), langs.sortedBy { SubLangs.rank(it, "eng") })
    }

    @Test
    fun escolheFaixaDoVideoAntesDaExternaENaoLigaIdiomaErrado() {
        // Mesmo idioma nos dois lados, a faixa do vídeo ganha
        assertEquals(SubLangs.Pick(true, 1), SubLangs.choose(listOf("eng", "pt-BR"), listOf("pob"), "pob"))
        // Idioma exato só na externa ganha do vizinho no vídeo
        assertEquals(SubLangs.Pick(false, 1), SubLangs.choose(listOf("por"), listOf("eng", "pob"), "pob"))
        // Sem o exato, o vizinho serve
        assertEquals(SubLangs.Pick(true, 0), SubLangs.choose(listOf("por"), listOf("eng"), "pob"))
        // Faixa sem idioma marcado só quando nada melhor existe
        assertEquals(SubLangs.Pick(true, 1), SubLangs.choose(listOf("eng", "und"), listOf("fre"), "pob"))
        // Nada no idioma escolhido, nada liga
        assertNull(SubLangs.choose(listOf("eng"), listOf("fre"), "pob"))
        assertEquals(SubLangs.Pick(false, 0), SubLangs.choose(emptyList(), listOf("en"), "eng"))
    }

    @Test
    fun reconheceForcadaPelaMarcaOuPeloNome() {
        assertTrue(SubLangs.isForced(true, null))
        assertTrue(SubLangs.isForced(false, "Português (Brasil) Forced"))
        assertTrue(SubLangs.isForced(false, "PT-BR forçada"))
        assertTrue(SubLangs.isForced(false, "Español forzados"))
        assertTrue(SubLangs.isForced(false, "Français forcé"))
        assertTrue(SubLangs.isForced(false, "pob.forced.srt"))
        assertFalse(SubLangs.isForced(false, "Português (Brasil)"))
        assertFalse(SubLangs.isForced(false, "Reinforced.Concrete.2020.pt-BR"))
        assertFalse(SubLangs.isForced(false, "Star.Wars.The.Force.Awakens.pt-BR"))
        assertFalse(SubLangs.isForced(false, null))
    }

    @Test
    fun legendaCompletaNaoLigaForcadaQuandoAForcadaVemPrimeiro() {
        // Arquivo com "PT-BR forçada" antes da completa. A completa é que liga, e sem completa nada liga.
        assertEquals(SubLangs.Pick(true, 1), SubLangs.choose(listOf("pob", "pob"), emptyList(), "pob", embeddedForced = listOf(true, false)))
        assertNull(SubLangs.choose(listOf("pob"), emptyList(), "pob", embeddedForced = listOf(true)))
        assertNull(SubLangs.choose(emptyList(), listOf("pob"), "pob", externalForced = listOf(true)))
        // Sem a informação de forçada o comportamento é o de sempre
        assertEquals(SubLangs.Pick(true, 0), SubLangs.choose(listOf("pob", "pob"), emptyList(), "pob"))
    }

    @Test
    fun forcadaLigaNoIdiomaDoPerfilEPreferePertoDoExato() {
        val forced = listOf(false, true)
        // Só a forçada do idioma do perfil entra, a completa fica de fora
        assertEquals(SubLangs.Pick(true, 1), SubLangs.chooseForced(listOf("pob", "pob"), emptyList(), "pob", forced, emptyList()))
        // Forçada do vídeo ganha da externa no mesmo idioma
        assertEquals(SubLangs.Pick(true, 0), SubLangs.chooseForced(listOf("pob"), listOf("pob"), "pob", listOf(true), listOf(true)))
        // Exata só na externa ganha da vizinha no vídeo
        assertEquals(SubLangs.Pick(false, 0), SubLangs.chooseForced(listOf("por"), listOf("pob"), "pob", listOf(true), listOf(true)))
        // Vizinha serve quando não há a exata
        assertEquals(SubLangs.Pick(true, 0), SubLangs.chooseForced(listOf("por"), emptyList(), "pob", listOf(true), emptyList()))
    }

    @Test
    fun forcadaNaoLigaIdiomaErradoMasAceitaSemIdiomaSoNoVideo() {
        assertNull(SubLangs.chooseForced(listOf("eng"), listOf("fre"), "pob", listOf(true), listOf(true)))
        // Nenhuma forçada, nenhuma liga
        assertNull(SubLangs.chooseForced(listOf("pob"), listOf("pob"), "pob", listOf(false), listOf(false)))
        // Forçada sem idioma marcado só vale como último recurso, e só a do vídeo
        assertEquals(SubLangs.Pick(true, 1), SubLangs.chooseForced(listOf("eng", "und"), emptyList(), "pob", listOf(false, true), emptyList()))
        assertNull(SubLangs.chooseForced(emptyList(), listOf("und"), "pob", emptyList(), listOf(true)))
    }
}
