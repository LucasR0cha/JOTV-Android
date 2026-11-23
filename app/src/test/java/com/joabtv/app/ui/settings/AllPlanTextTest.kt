package com.joabtv.app.ui.settings

import com.joabtv.app.data.accounts.NuvioProfile
import com.joabtv.app.data.local.NuvioPlan
import com.joabtv.app.data.local.NuvioPlanKind
import com.joabtv.app.data.local.NuvioPlanStep
import com.joabtv.app.data.local.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AllPlanTextTest {
    private val nuvio = listOf(NuvioProfile(1, "Ana", false), NuvioProfile(2, "Bia", false), NuvioProfile(3, "Caio", false), NuvioProfile(4, "Duda", false))
    private val jotv = listOf(Profile("main", "Davi"), Profile("p2", "Bia aqui"))

    @Test fun textoSaiDoPlano() {
        val plan = NuvioPlan(
            listOf(
                NuvioPlanStep(1, NuvioPlanKind.CURRENT, "main"), NuvioPlanStep(2, NuvioPlanKind.REUSE, "p2"),
                NuvioPlanStep(3, NuvioPlanKind.CREATE), NuvioPlanStep(4, NuvioPlanKind.CREATE),
            ),
            1,
        )
        assertEquals(
            "Cada um fica com os próprios addons e plugins. Ana entra no perfil Davi. Bia volta para o perfil Bia aqui. " +
                "Caio e Duda viram perfis novos. 1 fica de fora pelo limite de 5 perfis.",
            allPlanText(plan, nuvio, jotv),
        )
    }

    @Test fun semPerfilAtualNaoPrometeOAtual() {
        val t = allPlanText(NuvioPlan(listOf(NuvioPlanStep(1, NuvioPlanKind.CREATE)), 0), nuvio, jotv)
        assertEquals("Cada um fica com os próprios addons e plugins. Ana vira um perfil novo.", t)
        assertFalse(t.contains("Davi"))
    }
}
