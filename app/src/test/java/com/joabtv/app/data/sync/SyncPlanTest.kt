package com.joabtv.app.data.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Os mesmos cenários de tests/history-sync.test.cjs da versão web. */
class SyncPlanTest {
    private fun row(id: String, s: Int, e: Int, at: Long, series: Boolean = s > 0, pos: Long = 1000) =
        RemoteRow(id, series, s, e, pos, 5000, at, SyncPlan.progressKey(id, s, e))

    // ── chaves e ids ────────────────────────────────────────────────────────────

    @Test fun chavesDeProgressoEVideo() {
        assertEquals("tt1_s2e3", SyncPlan.progressKey("tt1", 2, 3))
        assertEquals("tt1", SyncPlan.progressKey("tt1", 0, 0))
        assertEquals("tt1:2:3", SyncPlan.videoId("tt1", true, 2, 3))
        assertEquals("tt1", SyncPlan.videoId("tt1", false, 0, 0))
        assertEquals("tt1", SyncPlan.baseId("tt1:2:3"))
        assertEquals("tmdb:5", SyncPlan.baseId("tmdb:5:1:2"))
        assertEquals(1 to 3, SyncPlan.parseEpisode("T1:E3"))
        assertEquals(0 to 0, SyncPlan.parseEpisode(""))
    }

    @Test fun idRemotoPrefereOLembradoDepoisImdbDepoisTmdb() {
        assertEquals("tmdb:9", SyncPlan.pickRemoteId("tmdb:9", "tt1", 9))
        assertEquals("tt1", SyncPlan.pickRemoteId(null, "tt1", 9))
        assertEquals("tmdb:9", SyncPlan.pickRemoteId(null, null, 9))
        assertEquals("tmdb:9", SyncPlan.pickRemoteId("", "kitsu:1", 9))
    }

    @Test fun mapaDeIdsRespeitaOTeto() {
        val m = LinkedHashMap<String, Int>()
        (1..3005).forEach { m["k$it"] = it }
        SyncPlan.cap(m)
        assertEquals(3000, m.size)
        assertFalse(m.containsKey("k1"))
        assertTrue(m.containsKey("k3005"))
    }

    // ── escolha e inferência ────────────────────────────────────────────────────

    @Test fun serieComVariasLinhasEscolheAMaisRecente() {
        val latest = SyncPlan.latestPerTitle(listOf(row("tt1", 1, 1, 100), row("tt1", 1, 4, 300), row("tt1", 1, 2, 200), row("tt2", 0, 0, 50, series = false)))
        assertEquals(2, latest.size)
        assertEquals(4, latest.first { it.contentId == "tt1" }.episode)
    }

    @Test fun remocaoSoComPuxadaCompletaETituloInteiro() {
        val prev = SyncPlan.snapshotOf(listOf(row("tt1", 1, 1, 100), row("tt1", 1, 2, 110), row("tt2", 0, 0, 90, series = false)))
        // Série ainda tem um episódio no remoto, então continua viva. Só o filme sumiu.
        val now = SyncPlan.snapshotOf(listOf(row("tt1", 1, 2, 110)))
        assertEquals(setOf(false to "tt2"), SyncPlan.goneTitles(prev, now, complete = true))
        // Puxada incompleta (servidor pode ter cortado a lista) nunca infere remoção.
        assertTrue(SyncPlan.goneTitles(prev, now, complete = false).isEmpty())
        assertTrue(SyncPlan.complete(899))
        assertFalse(SyncPlan.complete(900))
    }

    @Test fun vistosQueSumiramSoComPuxadaCompleta() {
        assertEquals(setOf("tt1|1|2"), SyncPlan.goneSeen(setOf("tt1|1|1", "tt1|1|2"), setOf("tt1|1|1"), true))
        assertTrue(SyncPlan.goneSeen(setOf("tt1|1|1"), emptySet(), false).isEmpty())
    }

    @Test fun puxadaVaziaNuncaApagaNada() {
        val prev = SyncPlan.snapshotOf(listOf(row("tt1", 0, 0, 100, series = false), row("tt2", 0, 0, 90, series = false)))
        assertTrue(SyncPlan.goneTitles(prev, emptyList(), complete = true).isEmpty())
        assertTrue(SyncPlan.goneSeen(setOf("tt1|1|1", "tt1|1|2"), emptySet(), true).isEmpty())
    }

    @Test fun sumicoEmMassaDeTitulosNaoApaga() {
        val ten = (1..10).map { row("tt$it", 0, 0, 100L + it, series = false) }
        val prev = SyncPlan.snapshotOf(ten)
        // 8 de 10 sumiram. É resposta cortada ou troca de conta, e não 8 remoções de uma vez.
        assertTrue(SyncPlan.goneTitles(prev, SyncPlan.snapshotOf(ten.take(2)), complete = true).isEmpty())
        // 3 de 10 sumiram. É remoção de verdade.
        assertEquals(setOf(false to "tt8", false to "tt9", false to "tt10"), SyncPlan.goneTitles(prev, SyncPlan.snapshotOf(ten.take(7)), complete = true))
        // 5 de 20 sumiram. Passa do mínimo mas não é a maioria.
        val twenty = SyncPlan.snapshotOf((1..20).map { row("tt$it", 0, 0, 100L + it, series = false) })
        assertEquals(5, SyncPlan.goneTitles(twenty, twenty.take(15), complete = true).size)
    }

    @Test fun sumicoEmMassaDeVistosNaoApaga() {
        val prev = (1..30).map { "tt1|1|$it" }.toSet()
        assertTrue(SyncPlan.goneSeen(prev, prev.take(5).toSet(), true).isEmpty())
        assertEquals(5, SyncPlan.goneSeen(prev, prev.take(25).toSet(), true).size)
    }

    @Test fun chaveDeFilmeNaoEhEpisodioEFilmeVistoNaoSeDesmarca() {
        assertTrue(SyncPlan.isEpisodeKey("tt1|1|2"))
        assertFalse(SyncPlan.isEpisodeKey("tt1|0|0"))
        assertFalse(SyncPlan.isEpisodeKey("tt1"))
        assertEquals(
            setOf("tt2|1|2"),
            SyncPlan.goneSeen(setOf("tt1|0|0", "tt2|1|1", "tt2|1|2"), setOf("tt2|1|1"), true),
        )
    }

    @Test fun duracaoDeMarcadorNaoSobeComoProgresso() {
        assertFalse(SyncPlan.pushable(1))
        assertFalse(SyncPlan.pushable(999))
        assertTrue(SyncPlan.pushable(1000))
        assertTrue(SyncPlan.pushable(5_400_000))
    }

    @Test fun horaFuturaDeOutroAparelhoFicaParadaNaPrimeiraVista() {
        // Hora no passado vale como veio.
        assertEquals(100L, SyncPlan.effectiveRemote(100, 200, null))
        // Hora no futuro (relógio adiantado lá) vira a hora em que foi vista pela primeira vez.
        assertEquals(200L, SyncPlan.effectiveRemote(500, 200, null))
        // E não anda com o relógio nas puxadas seguintes, senão sempre venceria o que a pessoa assistiu aqui depois.
        assertEquals(200L, SyncPlan.effectiveRemote(500, 300, 200))
        assertEquals(200L, SyncPlan.effectiveRemote(500, 499, 200))
        // Mesmo quando o relógio daqui passa da hora que veio, a hora congelada continua valendo. Se a crua voltasse a valer
        // nesse momento, ela cobriria o que a pessoa assistiu aqui entre a primeira vista e agora.
        assertEquals(200L, SyncPlan.effectiveRemote(500, 600, 200))
        // Sem registro de primeira vista e com hora no passado, vale a hora crua.
        assertEquals(500L, SyncPlan.effectiveRemote(500, 600, null))
    }

    @Test fun linhaEnviadaPeloAparelhoNaoContaParaInferirRemocao() {
        val prev = SyncPlan.snapshotOf(listOf(row("tt1", 0, 0, 100, series = false), row("tt2", 0, 0, 90, series = false)))
            .map { if (it.contentId == "tt2") it.copy(pushed = true) else it }
        val now = SyncPlan.snapshotOf(listOf(row("tt3", 0, 0, 50, series = false)))
        // tt1 sumiu de verdade da conta. tt2 foi enviado agora há pouco e o servidor pode não ter gravado, então não conta.
        assertEquals(setOf(false to "tt1"), SyncPlan.goneTitles(prev, now, complete = true))
    }

    @Test fun remocaoNaoApagaDadoQueOutroAppMexeuDepois() {
        assertTrue(SyncPlan.removalStands(null, 1000))
        assertTrue(SyncPlan.removalStands(500, 1000))
        assertTrue(SyncPlan.removalStands(1000, 1000))
        // Visto em outro aparelho depois. A remoção velha não leva isso (e a puxada traz o título de volta).
        assertFalse(SyncPlan.removalStands(1001, 1000))
    }

    @Test fun identificadorDaLigacaoNaoGuardaOEmailEMudaComContaOuPerfil() {
        val a = SyncPlan.linkId("Maria@Exemplo.com", 1)
        assertEquals(32, a.length)
        assertFalse(a.contains("maria", ignoreCase = true))
        assertEquals(a, SyncPlan.linkId(" maria@exemplo.com ", 1))
        assertFalse(a == SyncPlan.linkId("maria@exemplo.com", 2))
        assertFalse(a == SyncPlan.linkId("outra@exemplo.com", 1))
        assertFalse(SyncPlan.linkId("maria@exemplo.com", null) == a)
    }

    @Test fun retratoLevaTemporadaEpisodioEHoraLimitada() {
        val now = System.currentTimeMillis()
        val snap = SyncPlan.snapshotOf(listOf(row("tt1", 2, 5, now + 99_999_999_999L), row("tt2", 0, 0, 123, series = false)))
        assertEquals(2, snap[0].season)
        assertEquals(5, snap[0].episode)
        assertTrue(snap[0].at <= now + 3_600_000L + 1000)
        assertEquals(123L, snap[1].at)
        assertEquals(0, snap[1].season)
    }

    @Test fun enviaSoQuandoALocalEMaisNovaENuncaPosicaoZero() {
        assertTrue(SyncPlan.shouldPush(200, 1000, 100))
        assertTrue(SyncPlan.shouldPush(200, 1000, null))
        assertFalse(SyncPlan.shouldPush(100, 1000, 100))
        assertFalse(SyncPlan.shouldPush(100, 1000, 200))
        assertFalse(SyncPlan.shouldPush(200, 0, null))
        assertTrue(SyncPlan.finished(90, 100))
        assertFalse(SyncPlan.finished(89, 100))
        assertFalse(SyncPlan.finished(10, 0))
    }

    @Test fun relogioAtrasadoNaoImpedeEnvioDeQuemTocouDepoisDaPuxada() {
        val min = 60_000L
        // Tocou agora (real) e o relógio daqui está 3 min atrás do remoto. Manda, com hora acima da do remoto.
        assertTrue(SyncPlan.shouldPush(1000 * min - 3 * min, 1000, 1000 * min, real = true))
        assertEquals(1000 * min + 1, SyncPlan.pushAt(1000 * min - 3 * min, 1000 * min))
        // Atrás por mais que a tolerância (remoto de verdade mais novo) não pisa.
        assertFalse(SyncPlan.shouldPush(1000 * min - 6 * min, 1000, 1000 * min, real = true))
        // Semente da ligação da conta nunca cobre o remoto mais novo, mesmo dentro da tolerância.
        assertFalse(SyncPlan.shouldPush(1000 * min - 3 * min, 1000, 1000 * min, real = false))
        // Hora local maior fica como está.
        assertEquals(5000L, SyncPlan.pushAt(5000, 4000))
        assertEquals(5000L, SyncPlan.pushAt(5000, null))
    }

    @Test fun horaDeForaNaoPassaDeUmaHoraNoFuturo() {
        val now = 1_000_000_000L
        assertEquals(now - 5, SyncPlan.clampRemote(now - 5, now))
        assertEquals(now + 3_600_000L, SyncPlan.clampRemote(now + 99_999_999_999L, now))
    }

    // ── corpos do Nuvio ─────────────────────────────────────────────────────────

    @Test fun corpoDoProgressoDeSerieTemOFormatoDaEspecificacao() {
        val b = SyncPlan.progressBody(1, listOf(PushRow("tt0944947", true, 1, 2, 1234000, 3300000, 1763200000000)), "jotv-abc")
        assertEquals(1, b.getInt("p_profile_id"))
        assertEquals("jotv-abc", b.getString("p_origin_client_id"))
        val e = b.getJSONArray("p_entries").getJSONObject(0)
        assertEquals("tt0944947", e.getString("content_id"))
        assertEquals("series", e.getString("content_type"))
        assertEquals("tt0944947:1:2", e.getString("video_id"))
        assertEquals(1, e.getInt("season"))
        assertEquals(2, e.getInt("episode"))
        assertEquals(1234000L, e.getLong("position"))
        assertEquals(3300000L, e.getLong("duration"))
        assertEquals(1763200000000L, e.getLong("last_watched"))
        assertEquals("tt0944947_s1e2", e.getString("progress_key"))
    }

    @Test fun corpoDoProgressoUsaAChaveQueAContaJaTem() {
        val b = SyncPlan.progressBody(1, listOf(PushRow("tt1", true, 1, 2, 10, 100, 5, key = "chave-da-conta"), PushRow("tt2", false, 0, 0, 10, 100, 5, key = " ")), null)
        val arr = b.getJSONArray("p_entries")
        assertEquals("chave-da-conta", arr.getJSONObject(0).getString("progress_key"))
        // Chave em branco cai na calculada.
        assertEquals("tt2", arr.getJSONObject(1).getString("progress_key"))
    }

    @Test fun corpoDoProgressoDeFilmeTemTemporadaNulaEChaveIgualAoId() {
        val b = SyncPlan.progressBody(2, listOf(PushRow("tt0111161", false, 0, 0, 10, 100, 5)), null)
        assertFalse(b.has("p_origin_client_id"))
        val e = b.getJSONArray("p_entries").getJSONObject(0)
        assertEquals("movie", e.getString("content_type"))
        assertEquals("tt0111161", e.getString("video_id"))
        assertTrue(e.isNull("season"))
        assertTrue(e.isNull("episode"))
        assertEquals("tt0111161", e.getString("progress_key"))
    }

    @Test fun corpoDosVistosEDasRemocoes() {
        val w = SyncPlan.watchedBody(1, listOf(PushSeen("tt1", true, "Série", 1, 2, 9), PushSeen("tt2", false, "Filme", 0, 0, 8)), "jotv-x")
        val items = w.getJSONArray("p_items")
        assertEquals("Série", items.getJSONObject(0).getString("title"))
        assertEquals(2, items.getJSONObject(0).getInt("episode"))
        assertEquals(9L, items.getJSONObject(0).getLong("watched_at"))
        assertTrue(items.getJSONObject(1).isNull("season"))
        val dp = SyncPlan.deleteProgressBody(1, listOf("tt1_s1e2", "tt2"), "jotv-x")
        assertEquals("tt1_s1e2", dp.getJSONArray("p_keys").getString(0))
        val dw = SyncPlan.deleteWatchedBody(1, listOf(Triple("tt1", 1, 2), Triple("tt2", 0, 0)), null)
        val keys = dw.getJSONArray("p_keys")
        assertEquals("tt1", keys.getJSONObject(0).getString("content_id"))
        assertEquals(1, keys.getJSONObject(0).getInt("season"))
        assertTrue(keys.getJSONObject(1).isNull("season"))
    }

    // ── itens do Stremio ────────────────────────────────────────────────────────

    @Test fun itemNovoDoStremioNasceTemporarioERemovido() {
        val it = SyncPlan.stremioItem(null, "tt1", true, "Série", "https://p/x.jpg", "tt1:1:2", 1000, 5000, 1763200000000, 1763200100000)
        assertEquals("tt1", it.getString("_id"))
        assertEquals("series", it.getString("type"))
        assertTrue(it.getBoolean("removed"))
        assertTrue(it.getBoolean("temp"))
        val st = it.getJSONObject("state")
        assertEquals(1000L, st.getLong("timeOffset"))
        assertEquals(5000L, st.getLong("duration"))
        assertEquals("tt1:1:2", st.getString("video_id"))
        assertEquals("2025-11-15T09:46:40Z", st.getString("lastWatched"))
        assertEquals(SyncPlan.iso(1763200100000), it.getString("_mtime"))
    }

    @Test fun itemExistenteDoStremioSoTrocaOProgresso() {
        val existing = JSONObject(
            """{"_id":"tt1","name":"X","type":"series","removed":false,"temp":false,"_ctime":"2020-01-01T00:00:00.000Z","_mtime":"2020-01-02T00:00:00.000Z",
               "state":{"lastWatched":"2020-01-02T00:00:00.000Z","timeOffset":5,"duration":9,"video_id":"tt1:1:1","watched":"abc","timesWatched":3},"extra":{"a":1}}""",
        )
        val copy = existing.toString()
        val it = SyncPlan.stremioItem(existing, "tt1", true, "X", "", "tt1:1:2", 1000, 5000, 1763200000000, 1763200100000)
        // A cópia que veio da conta não é alterada (o item novo é outro objeto).
        assertEquals(copy, existing.toString())
        assertFalse(it.getBoolean("removed"))
        assertFalse(it.getBoolean("temp"))
        assertEquals("2020-01-01T00:00:00.000Z", it.getString("_ctime"))
        assertEquals(1, it.getJSONObject("extra").getInt("a"))
        val st = it.getJSONObject("state")
        assertEquals("abc", st.getString("watched"))
        assertEquals(3, st.getInt("timesWatched"))
        assertEquals(1000L, st.getLong("timeOffset"))
        assertEquals("tt1:1:2", st.getString("video_id"))
    }

    @Test fun tirarDoContinuarAssistindoNoStremioZeraOTimeOffset() {
        val existing = JSONObject("""{"_id":"tt1","state":{"timeOffset":900,"duration":1000}}""")
        val cleared = SyncPlan.stremioCleared(existing, 1763200100000)!!
        assertEquals(0L, cleared.getJSONObject("state").getLong("timeOffset"))
        assertEquals(1000L, cleared.getJSONObject("state").getLong("duration"))
        assertNull(SyncPlan.stremioCleared(null, 1))
    }
}
