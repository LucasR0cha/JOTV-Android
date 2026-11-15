package com.joabtv.app.ui.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvLayoutTest {
    @Test fun dpiDoFabricanteNaoAlteraOTamanhoDaAreaDeTrabalho() {
        for ((width, height) in listOf(1280 to 720, 1920 to 1080, 3840 to 2160)) {
            for (systemDensity in listOf(1f, 1.5f, 2f, 3f, 4f)) {
                val density = tvViewportDensity(width, height)
                assertEquals(960f, width / density, .01f)
                assertEquals(540f, height / density, .01f)
                val oldWidthDp = width / systemDensity
                assertEquals(oldWidthDp * systemDensity / density, width / density, .01f)
            }
        }
    }
    @Test fun proporcaoDaInterfaceIgualEmHdFullHdEQuatroK() {
        assertEquals(1280f / 120, tvRem(1280, 720), .001f)
        assertEquals(16f, tvRem(1920, 1080), .001f)
        assertEquals(32f, tvRem(3840, 2160), .001f)
    }

    @Test fun gradePerdeColunasQuandoOTamanhoDaInterfaceCresce() {
        val colunas = com.joabtv.app.data.local.InterfaceSize.entries.map { tvColumnsFor(8, it.scale) }
        assertEquals(listOf(8, 7, 7), colunas)
        assertEquals(1, tvColumnsFor(2, 1.4f))
        assertEquals(2, tvColumnsFor(3, 1.4f))
    }

    @Test fun telaLargaOuAltaRespeitaADimensaoMenor() {
        assertEquals(16f, tvRem(2560, 1080), .001f)
        assertEquals(16f, tvRem(1920, 1200), .001f)
        assertEquals(12f, tvRem(1440, 1080), .001f)
    }
}
