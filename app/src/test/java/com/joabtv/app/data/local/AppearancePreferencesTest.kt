package com.joabtv.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearancePreferencesTest {
    @Test fun instalacaoOuAtualizacaoComecaEmSuave() {
        assertEquals(InterfaceMotion.SMOOTH, AppearancePreferences().motion)
        assertEquals(InterfaceMotion.SMOOTH, InterfaceMotion.fromKey(null))
        assertEquals(InterfaceMotion.SMOOTH, InterfaceMotion.fromKey("desconhecido"))
    }

    @Test fun valoresPersistidosMantemAsEscolhasDoPerfil() {
        AppTheme.entries.forEach { assertEquals(it, AppTheme.fromKey(it.key)) }
        InterfaceMotion.entries.forEach { assertEquals(it, InterfaceMotion.fromKey(it.key)) }
        assertEquals(AppTheme.GRAPHITE, AppTheme.fromKey(null))
        assertEquals(AppTheme.GRAPHITE, AppTheme.fromKey("desconhecido"))
    }

    @Test fun aparenciaSaiJuntoAoRemoverOProprioPerfil() {
        assertTrue("pref_theme" in SCOPED_KEYS)
        assertTrue("pref_motion" in SCOPED_KEYS)
        assertEquals("pref_motion", scopedKey("pref_motion", MAIN_PROFILE))
        assertEquals("p.segundo.pref_motion", scopedKey("pref_motion", "segundo"))
    }

    @Test fun menuComecaNaLateralEAceitaSoAsChavesConhecidas() {
        assertEquals(MenuStyle.SIDE, AppearancePreferences().menu)
        assertEquals(MenuStyle.SIDE, MenuStyle.fromKey(null))
        assertEquals(MenuStyle.SIDE, MenuStyle.fromKey("desconhecido"))
        assertEquals(listOf("side", "top", "hidden"), MenuStyle.entries.map { it.key })
        MenuStyle.entries.forEach { assertEquals(it, MenuStyle.fromKey(it.key)) }
        assertTrue("pref_menu_style" in SCOPED_KEYS)
        assertEquals("p.segundo.pref_menu_style", scopedKey("pref_menu_style", "segundo"))
    }

    @Test fun tamanhoDaInterfaceComecaNoPadraoEAceitaSoAsChavesConhecidas() {
        assertEquals(InterfaceSize.STANDARD, AppearancePreferences().uiSize)
        assertEquals(1f, InterfaceSize.STANDARD.scale, 0f)
        assertEquals(InterfaceSize.STANDARD, InterfaceSize.fromKey(null))
        assertEquals(InterfaceSize.STANDARD, InterfaceSize.fromKey("desconhecido"))
        InterfaceSize.entries.forEach { assertEquals(it, InterfaceSize.fromKey(it.key)) }
        // Cada passo é maior que o anterior, senão a escolha na tela não muda nada.
        assertTrue(InterfaceSize.entries.map { it.scale }.zipWithNext().all { (a, b) -> b > a })
        assertTrue("pref_ui_size" in SCOPED_KEYS)
        assertEquals("p.segundo.pref_ui_size", scopedKey("pref_ui_size", "segundo"))
    }
}
