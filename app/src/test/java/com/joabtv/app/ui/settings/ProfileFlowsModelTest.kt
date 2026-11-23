package com.joabtv.app.ui.settings

import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.BatchImport
import com.joabtv.app.data.accounts.BatchItem
import com.joabtv.app.data.accounts.BatchState
import com.joabtv.app.data.accounts.ImportPart
import com.joabtv.app.data.accounts.Load
import com.joabtv.app.data.accounts.PartRecord
import com.joabtv.app.data.local.ProfileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileFlowsModelTest {
    @Test fun `copia vem com tudo marcado menos o debrid e infantil nao leva plugins`() {
        assertEquals(ProfileCategory.entries, copyCategories(false))
        assertFalse(ProfileCategory.PLUGINS in copyCategories(true))
        assertEquals(setOf(ProfileCategory.ADDONS, ProfileCategory.PLUGINS, ProfileCategory.CATALOG, ProfileCategory.LIVE), defaultCopySelection(false))
        assertEquals(setOf(ProfileCategory.ADDONS, ProfileCategory.CATALOG, ProfileCategory.LIVE), defaultCopySelection(true))
    }

    @Test fun `linha da categoria diz de quem sao os dados`() {
        assertEquals("Próprios deste perfil", categoryLine(null, tv = true))
        assertEquals("Usando os de Ana. Mudar vale para os dois. OK deixa de usar.", categoryLine("Ana", tv = true))
        assertEquals("Usando os de Ana. Mudar vale para os dois. Toque e deixa de usar.", categoryLine("Ana", tv = false))
        assertEquals("Catálogo e Início", categoryLabel(ProfileCategory.CATALOG))
    }

    @Test fun `remover avisa quem fica com copia`() {
        assertEquals("Minha Lista, histórico, addons e plugins desse perfil somem.", removeProfileBody(emptyList()))
        assertEquals("Minha Lista, histórico, addons e plugins desse perfil somem. Bia fica com uma cópia do que usava dele.", removeProfileBody(listOf("Bia")))
        assertTrue(removeProfileBody(listOf("Bia", "Caio", "Duda")).endsWith(" Bia, Caio e Duda ficam com uma cópia do que usava dele."))
    }

    @Test fun `escolha so aparece quando ha o que escolher`() {
        // Um perfil dos dois lados vai direto, em um clique.
        assertEquals(false, importNeedsChoice(Account.NUVIO, 1, Load.OK, 1))
        assertEquals(false, importNeedsChoice(Account.STREMIO, 1, Load.IDLE, 1))
        // Nuvio com mais de um perfil, ou aparelho com mais de um perfil, abre a escolha.
        assertEquals(true, importNeedsChoice(Account.NUVIO, 1, Load.OK, 3))
        assertEquals(true, importNeedsChoice(Account.STREMIO, 2, Load.IDLE, 1))
        assertEquals(true, importNeedsChoice(Account.NUVIO, 2, Load.LOADING, 1))
        // Sem a lista da Nuvio ainda, espera. Falhou, vai do Principal sem perguntar.
        assertNull(importNeedsChoice(Account.NUVIO, 1, Load.LOADING, 1))
        assertEquals(false, importNeedsChoice(Account.NUVIO, 1, Load.FAIL, 1))
    }

    @Test fun `rotulo do botao de trazer`() {
        val all = Account.NUVIO.parts
        assertEquals("Trazer todos os perfis", importGoLabel(all, all.size, all = true))
        assertEquals("Trazer tudo", importGoLabel(all, all.size, all = false))
        assertEquals("Trazer addons", importGoLabel(listOf(ImportPart.ADDONS), all.size, all = true))
        assertEquals("Trazer 2 de ${all.size}", importGoLabel(listOf(ImportPart.ADDONS, ImportPart.PLUGINS), all.size, all = false))
    }

    private fun item(name: String, state: BatchState, results: Map<ImportPart, PartRecord> = emptyMap()) =
        BatchItem(1, name, "p$name", name, state, results)

    @Test fun `andamento e resumo do trazer todos`() {
        val running = BatchImport(Account.NUVIO, listOf(item("Ana", BatchState.DONE), item("Bia", BatchState.RUNNING), item("Caio", BatchState.WAITING)))
        assertEquals("Perfil 2 de 3, Bia", batchStepText(running))
        assertNull(batchStepText(running.copy(running = false)))
        assertNull(batchStepText(null))

        val done = BatchImport(
            Account.NUVIO,
            listOf(
                item("Ana", BatchState.DONE, mapOf(ImportPart.ADDONS to PartRecord(true, n = 3, skip = 1), ImportPart.PLUGINS to PartRecord(true, n = 1), ImportPart.LIBRARY to PartRecord(true, list = 12))),
                item("Bia", BatchState.FAILED, mapOf(ImportPart.ADDONS to PartRecord(false, why = "x"), ImportPart.PLUGINS to PartRecord(true))),
            ),
            leftOut = 1, running = false,
        )
        assertEquals("2 perfis trazidos. Cada um ficou com os próprios addons e plugins. 1 perfil ficou de fora pelo limite de perfis.", batchSummaryBody(done))
        assertEquals("4 addons, 1 repositório de plugins, 12 na Minha Lista" to false, batchItemLine(done.items[0]))
        assertEquals("Faltou addons" to true, batchItemLine(done.items[1]))
        assertEquals("Parou no meio. Já existem 5 perfis. O que já veio ficou nos perfis.", batchSummaryBody(done.copy(error = "Já existem 5 perfis")))
    }

    @Test fun `resumo dos plugins igual ao da web`() {
        assertEquals("2 repositórios de plugins, 1 já estavam", partSummary(ImportPart.PLUGINS, PartRecord(true, n = 1, skip = 1)).text)
        assertEquals("Ligue os provedores em Plugins", partSummary(ImportPart.PLUGINS, PartRecord(true, n = 1, skip = 1)).sub)
        assertEquals("A conta não tem plugins", partSummary(ImportPart.PLUGINS, PartRecord(true)).text)
    }
}
