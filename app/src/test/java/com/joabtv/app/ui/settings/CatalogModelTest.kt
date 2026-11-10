package com.joabtv.app.ui.settings

import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.AccountSnap
import com.joabtv.app.data.accounts.ImportEntry
import com.joabtv.app.data.accounts.ImportLog
import com.joabtv.app.data.accounts.ImportPart
import com.joabtv.app.data.accounts.ImportRun
import com.joabtv.app.data.accounts.PartRecord
import com.joabtv.app.data.collections.CollectionFolder
import com.joabtv.app.data.collections.MediaCollection
import com.joabtv.app.data.local.AddonEntry
import com.joabtv.app.data.local.HomeCatalogRef
import com.joabtv.app.data.local.HomeRowPref
import com.joabtv.app.data.local.SystemRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class CatalogModelTest {

    private val utc = TimeZone.getTimeZone("UTC")
    private fun at(day: Int, h: Int, m: Int, month: Int = Calendar.NOVEMBER): Long =
        Calendar.getInstance(utc).apply { clear(); set(2026, month, day, h, m, 0) }.timeInMillis

    private val now = at(10, 18, 0)
    private val came = at(10, 14, 32)
    private val signed = AccountSnap(signedIn = true, email = "pessoa@teste.com", since = true)

    private fun parts(vararg p: Pair<String, PartRecord>) = ImportEntry(came, "pessoa@teste.com", "Principal", mapOf(*p))
    private val allOk = parts(
        "addons" to PartRecord(true, n = 5, skip = 2),
        "library" to PartRecord(true, list = 42, progress = 18),
        "collections" to PartRecord(true, n = 3),
        "debrid" to PartRecord(true, label = "Real-Debrid"),
    )

    private fun nuvio(snap: AccountSnap, e: ImportEntry? = null, run: ImportRun? = null) = accountView(Account.NUVIO, snap, e, run, now, utc)

    // ── Datas ──

    @Test
    fun `hoje ontem e depois a data`() {
        assertEquals("hoje às 14h32", whenText(came, now, utc))
        assertEquals("ontem às 14h32", whenText(at(9, 14, 32), now, utc))
        assertEquals("em 08/11 às 09h05", whenText(at(8, 9, 5), now, utc))
        assertEquals("hoje 14h32", whenShort(came, now, utc))
        assertEquals("ontem 23h59", whenShort(at(9, 23, 59), now, utc))
        assertEquals("08/11", whenShort(at(8, 9, 5), now, utc))
    }

    @Test
    fun `milhar com ponto`() {
        assertEquals("1.047", thousands(1047))
        assertEquals("23", thousands(23))
        assertEquals("1.234.567", thousands(1234567))
    }

    // ── Cartão da conta (matriz 4.1) ──

    @Test
    fun `sem conta o cartao oferece os dois jeitos de entrar`() {
        val v = nuvio(AccountSnap())
        assertNull(v.tag)
        assertEquals(listOf("Entrar com o celular", "Usar e-mail e senha"), v.acts.map { it.label })
        assertEquals(listOf("Traz addons, Minha Lista, progresso, coleções e debrid"), v.lines)
        assertEquals(listOf("Traz addons, Minha Lista e progresso"), accountView(Account.STREMIO, AccountSnap(), null, null, now, utc).lines)
        assertFalse(v.pending)
    }

    @Test
    fun `conta conectada sem nada trazido ja traz o Trazer tudo no cartao`() {
        val v = nuvio(signed)
        assertEquals(CardTag("Conectado", TagTone.ON), v.tag)
        assertEquals(listOf("Trazer tudo", "Mais"), v.acts.map { it.label })
        assertEquals("pessoa@teste.com. Ainda não trouxe nada para este perfil.", v.lines.single())
        assertTrue(v.pending)
    }

    @Test
    fun `conta de antes do registro nao vira pendencia`() {
        val v = nuvio(signed.copy(since = false))
        assertEquals("pessoa@teste.com. Trazer de novo só soma o que faltar.", v.lines.single())
        assertFalse(v.pending)
    }

    @Test
    fun `trazendo trava o botao e mostra a parte da vez`() {
        val run = ImportRun(ImportPart.entries, index = 2)
        val v = nuvio(signed, allOk, run)
        assertEquals("Trazendo coleções, 3 de 4 partes", v.lines.last())
        assertTrue(v.acts[0].busy)
        assertEquals("Trazendo…", v.acts[0].label)
        assertTrue(v.acts[1].blocked)
        assertEquals("Trazendo coleções", ImportRun(listOf(ImportPart.COLLECTIONS)).text)
    }

    @Test
    fun `trazido diz quando e o que veio`() {
        val v = nuvio(signed, allOk)
        assertEquals(CardTag("Conectado", TagTone.ON), v.tag)
        assertEquals("pessoa@teste.com, perfil Principal do Nuvio", v.lines[0])
        assertEquals("Trazido hoje às 14h32. 7 addons, 42 na Minha Lista, 3 coleções.", v.lines[1])
        assertEquals(listOf("Atualizar", "O que veio", "Mais"), v.acts.map { it.label })
        assertFalse(v.failed)
    }

    @Test
    fun `falha parcial nunca diz que veio tudo e oferece o que faltou`() {
        val e = parts(
            "addons" to PartRecord(true, n = 5),
            "library" to PartRecord(true, list = 42),
            "collections" to PartRecord(false, why = "O Nuvio não respondeu"),
            "debrid" to PartRecord(true),
        )
        val v = nuvio(signed, e)
        assertEquals(CardTag("Faltou parte", TagTone.WARN), v.tag)
        assertEquals("Trazido hoje às 14h32, mas as coleções não vieram.", v.lines[1])
        assertEquals(listOf("Tentar o que faltou", "O que veio", "Mais"), v.acts.map { it.label })
        assertEquals("retry", v.acts[0].id)
        assertTrue(v.failed)
        assertFalse(v.lines.joinToString(" ").contains("Tudo importado", ignoreCase = true))
        // Parte no singular e duas partes juntas.
        val one = parts("addons" to PartRecord(true, n = 1), "library" to PartRecord(false, why = "x"))
        assertEquals("Trazido hoje às 14h32, mas a Minha Lista não veio.", nuvio(signed, one).lines[1])
        val two = parts("addons" to PartRecord(false, why = "x"), "library" to PartRecord(true), "debrid" to PartRecord(false, why = "y"))
        assertEquals("Trazido hoje às 14h32, mas os addons e o debrid não vieram.", nuvio(signed, two).lines[1])
    }

    @Test
    fun `falha total mostra Nao veio e Tentar de novo`() {
        val e = parts("addons" to PartRecord(false, why = "a"), "library" to PartRecord(false, why = "b"))
        val v = nuvio(signed, e)
        assertEquals(CardTag("Não veio", TagTone.BAD), v.tag)
        assertEquals("O Nuvio não respondeu. Confira a internet e tente de novo.", v.lines[1])
        assertEquals(listOf("Tentar de novo", "Mais"), v.acts.map { it.label })
        assertTrue(v.failed)
    }

    @Test
    fun `sessao vencida pede para entrar de novo e o que veio continua aqui`() {
        val v = nuvio(AccountSnap(signedIn = false, lost = true), allOk)
        assertEquals(CardTag("Entre de novo", TagTone.WARN), v.tag)
        assertEquals("A conexão com o Nuvio venceu. O que já veio continua aqui.", v.lines.single())
        assertEquals(listOf("Entrar com o celular", "Mais"), v.acts.map { it.label })
    }

    @Test
    fun `stremio mostra so o que ele traz`() {
        val e = ImportEntry(came, "pessoa@teste.com", "", mapOf("addons" to PartRecord(true, n = 3), "library" to PartRecord(true, list = 9)))
        val v = accountView(Account.STREMIO, signed, e, null, now, utc)
        assertEquals("pessoa@teste.com", v.lines[0])
        assertEquals("Trazido hoje às 14h32. 3 addons, 9 na Minha Lista.", v.lines[1])
    }

    // ── O que veio ──

    @Test
    fun `resumo de cada parte`() {
        val addons = partSummary(ImportPart.ADDONS, PartRecord(true, n = 5, skip = 2, failed = 1, unusable = 2, rows = 16, rowsOn = 16))
        assertEquals(Mark.OK, addons.mark)
        assertEquals("7 addons, 2 já estavam", addons.text)
        assertEquals("16 fileiras novas na Início. 1 não respondeu. 2 só rodam no outro app", addons.sub)
        assertEquals("10 de 40 fileiras na Início. Escolha outras em Fileiras", partSummary(ImportPart.ADDONS, PartRecord(true, n = 1, rows = 40, rowsOn = 10)).sub)
        assertEquals("A conta não tem addons", partSummary(ImportPart.ADDONS, PartRecord(true)).text)
        assertEquals(Mark.NONE, partSummary(ImportPart.ADDONS, PartRecord(true)).mark)

        val lib = partSummary(ImportPart.LIBRARY, PartRecord(true, list = 42, progress = 18, nomatch = 3))
        assertEquals("42 na Minha Lista", lib.text)
        assertEquals("18 no Continuar assistindo. 3 sem par no TMDB", lib.sub)

        assertEquals("3 coleções", partSummary(ImportPart.COLLECTIONS, PartRecord(true, n = 3)).text)
        assertEquals("1 coleção", partSummary(ImportPart.COLLECTIONS, PartRecord(true, n = 1)).text)
        assertEquals("A conta não tem coleções", partSummary(ImportPart.COLLECTIONS, PartRecord(true)).text)

        assertEquals("Debrid conectado (Real-Debrid)", partSummary(ImportPart.DEBRID, PartRecord(true, label = "Real-Debrid")).text)
        // Conta sem chave não é erro, então a marca é neutra e o texto não tem "!".
        val none = partSummary(ImportPart.DEBRID, PartRecord(true))
        assertEquals(Mark.NONE, none.mark)
        assertEquals("A conta não tem chave de debrid", none.text)

        val bad = partSummary(ImportPart.COLLECTIONS, PartRecord(false, why = "O Nuvio não respondeu"))
        assertEquals(Mark.BAD, bad.mark)
        assertEquals("Coleções não vieram", bad.text)
        assertEquals("O Nuvio não respondeu", bad.sub)
    }

    // ── Foco inicial (3.1) ──

    @Test
    fun `foco inicial segue as regras na ordem`() {
        val home = homeView(HomeSnap(rows = 10), false)
        val none = nuvio(AccountSnap())
        val pending = nuvio(signed)
        val partial = nuvio(signed, parts("addons" to PartRecord(true), "collections" to PartRecord(false, why = "x")))
        val total = nuvio(signed, parts("addons" to PartRecord(false, why = "x")))
        val stremioPending = accountView(Account.STREMIO, signed, null, null, now, utc)
        // 4. Sem pendência, Organizar.
        assertEquals("home/organize", firstFocusKey(listOf(home, none)))
        // 2. Conta conectada sem nada trazido, o Trazer tudo dela.
        assertEquals("nuvio/all", firstFocusKey(listOf(home, pending, stremioPending)))
        assertEquals("stremio/all", firstFocusKey(listOf(home, none, stremioPending)))
        // 3. Última importação com falha, o Tentar dela.
        assertEquals("nuvio/retry", firstFocusKey(listOf(home, partial)))
        assertEquals("nuvio/all", firstFocusKey(listOf(home, total)))
        // 1. Volta de uma tela aberta pelo cartão leva ao botão que abriu, antes de tudo.
        assertEquals("addons/manage", firstFocusKey(listOf(home, pending), opened = "addons/manage"))
        // Importação em andamento, o foco fica no botão travado.
        val running = nuvio(signed, null, ImportRun(ImportPart.entries, 1))
        assertEquals("nuvio/all", firstFocusKey(listOf(home, running, stremioPending)))
        // Pendência ganha da falha.
        assertEquals("stremio/all", firstFocusKey(listOf(home, partial, stremioPending)))
    }

    @Test
    fun `perfil infantil so tem o cartao do perfil infantil`() {
        val cards = catalogCards(CatalogState(kids = true), now = now, tz = utc)
        assertEquals(listOf("kids"), cards.map { it.key })
        assertEquals("Início do perfil infantil", cards[0].title)
        assertEquals(listOf("Trocar de perfil"), cards[0].acts.map { it.label })
        assertEquals("kids/profiles", firstFocusKey(cards))
    }

    @Test
    fun `ordem dos cartoes e fixa`() {
        val s = CatalogState(nuvio = signed, log = ImportLog(mapOf("nuvio" to allOk)))
        assertEquals(listOf("home", "nuvio", "stremio", "addons", "cols"), catalogCards(s, now = now, tz = utc).map { it.key })
        assertEquals(listOf("home", "nuvio", "stremio", "addons", "cols"), catalogCards(CatalogState(), now = now, tz = utc).map { it.key })
    }

    // ── Demais cartões ──

    @Test
    fun `cartao de coleções segue a conta Nuvio`() {
        val none = collectionsView(ColsSnap(), CatalogState())
        assertEquals(listOf("Entrar no Nuvio", "Abrir link"), none.acts.map { it.label })
        assertEquals("Fileiras com pastas, como Marvel ou Anime. Nenhuma ainda.", none.lines.single())

        val connected = collectionsView(ColsSnap(), CatalogState(nuvio = signed))
        assertEquals(listOf("Trazer do Nuvio", "Abrir link"), connected.acts.map { it.label })
        assertEquals("O Nuvio pode ter coleções suas.", connected.lines.single())

        val pulled = collectionsView(ColsSnap(3, 14), CatalogState(nuvio = signed, log = ImportLog(mapOf("nuvio" to allOk))))
        assertEquals(listOf("Ver coleções", "Atualizar"), pulled.acts.map { it.label })
        assertEquals("3 coleções com 14 pastas, do Nuvio", pulled.lines.single())

        // Trouxe 3 e a pessoa apagou todas. O Nuvio pode ter, e o botão segue Trazer.
        val erased = collectionsView(ColsSnap(), CatalogState(nuvio = signed, log = ImportLog(mapOf("nuvio" to allOk))))
        assertEquals(listOf("Trazer do Nuvio", "Abrir link"), erased.acts.map { it.label })
        assertEquals("O Nuvio pode ter coleções suas.", erased.lines.single())
        // Trouxe e o Nuvio não tinha nenhuma.
        val empty = parts("collections" to PartRecord(true, n = 0), "addons" to PartRecord(true))
        assertEquals(
            "O Nuvio não tem coleções neste perfil. Traga de um link ou crie no celular.",
            collectionsView(ColsSnap(), CatalogState(nuvio = signed, log = ImportLog(mapOf("nuvio" to empty)))).lines.single(),
        )

        val bad = parts("collections" to PartRecord(false, why = "x"), "addons" to PartRecord(true))
        val failed = collectionsView(ColsSnap(), CatalogState(nuvio = signed, log = ImportLog(mapOf("nuvio" to bad))))
        assertEquals(listOf("Tentar de novo", "Abrir link"), failed.acts.map { it.label })
        assertEquals("As coleções do Nuvio não vieram.", failed.lines.single())

        val running = collectionsView(ColsSnap(), CatalogState(nuvio = signed, running = mapOf("nuvio" to ImportRun(listOf(ImportPart.COLLECTIONS)))))
        assertTrue(running.acts[0].busy)
        assertEquals("Chegando coleções…", running.lines.single())

        val link = collectionsView(ColsSnap(), CatalogState(), linkBusy = true)
        assertTrue(link.acts[1].busy)
        assertEquals("Trazendo…", link.acts[1].label)
    }

    @Test
    fun `cartao de addons tem sempre Explorar como primario`() {
        val none = addonsView(AddonsSnap(), CatalogState())
        assertEquals(listOf("Explorar", "Colar link"), none.acts.map { it.label })
        assertEquals("Nenhum addon. Eles trazem fontes para assistir e fileiras novas.", none.lines.single())
        assertEquals("Nenhum addon. O Nuvio pode ter addons seus.", addonsView(AddonsSnap(), CatalogState(nuvio = signed)).lines.single())

        val some = addonsView(AddonsSnap(7, 7, 16), CatalogState())
        assertEquals(listOf("Explorar", "Escolher fileiras", "Gerenciar"), some.acts.map { it.label })
        assertEquals("7 ativos, 16 fileiras na Início", some.lines.single())
        assertEquals("6 ativos, 1 desligado", addonsView(AddonsSnap(7, 6, 16), CatalogState()).lines.single())

        val missed = CatalogState(nuvio = signed, log = ImportLog(mapOf("nuvio" to parts("addons" to PartRecord(false, why = "x")))))
        assertEquals("Os addons do Nuvio não vieram.", addonsView(AddonsSnap(), missed).lines.single())
        assertEquals("Chegando addons…", addonsView(AddonsSnap(), CatalogState(running = mapOf("nuvio" to ImportRun(ImportPart.entries)))).lines.single())
    }

    @Test
    fun `cartao da Minha Inicio conta so o que aparece`() {
        assertEquals("10 fileiras das sugestões do JOTV.", homeView(HomeSnap(rows = 10), false).lines.single())
        assertEquals("Só seu progresso. Ligue as sugestões ou instale um addon.", homeView(HomeSnap(rows = 2, catalogOn = false), false).lines.single())
        assertEquals("23 fileiras, 16 de addons. Destaque Tops do momento.", homeView(HomeSnap(23, 16, 0, true, "Tops do momento"), false).lines.single())
        assertEquals("23 fileiras, 16 de addons e 3 de coleções.", homeView(HomeSnap(23, 16, 3), false).lines.single())
        assertEquals("1.047 fileiras. Ultra Max trouxe muitas.", homeView(HomeSnap(1047, 1030, 0, true, "", "Ultra Max" to 1030), false).lines.single())
        assertEquals("13 fileiras, 3 novas.", homeView(HomeSnap(13, 3, 0, fresh = 3), false).lines.single())
        assertEquals("Chegando fileiras novas…", homeView(HomeSnap(rows = 10), true).lines.single())
        assertEquals(listOf("Organizar", "Destaque Automático"), homeView(HomeSnap(), false).acts.map { it.label })
        assertEquals("Destaque Netflix", homeView(HomeSnap(hero = "Netflix"), false).acts[1].label)
    }

    @Test
    fun `fileira bloqueada nao conta na Minha Inicio`() {
        val a = "https://a.com"
        val cat = HomeRowPref(SystemRow.TRENDING_MOVIES.key)
        val prog = HomeRowPref(SystemRow.CONTINUE.key)
        val off = HomeRowPref(SystemRow.NOW_PLAYING.key, enabled = false)
        val addonRow = HomeRowPref("$a|movie|x", true, "", HomeCatalogRef(a, "movie", "x", "X", "Addon"))
        val col = MediaCollection("c1", "Marvel", folders = listOf(CollectionFolder("f", "Filmes")))
        val emptyCol = MediaCollection("c2", "Vazia")
        val colRow = HomeRowPref("col:c1", true, "", null, "Marvel")
        val emptyRow = HomeRowPref("col:c2", true, "", null, "Vazia")
        val layout = listOf(prog, cat, off, addonRow, colRow, emptyRow)

        val all = homeSnap(layout, true, "", listOf(AddonEntry(a)), listOf(col, emptyCol))
        assertEquals(4, all.rows)
        assertEquals(1, all.addonRows)
        assertEquals(1, all.colRows)
        // Sugestões desligadas tiram a fileira do catálogo. Addon desligado tira a dele.
        val noCatalog = homeSnap(layout, false, "", listOf(AddonEntry(a, enabled = false)), listOf(col, emptyCol))
        assertEquals(2, noCatalog.rows)
        assertEquals(0, noCatalog.addonRows)
        // Fileira vista antes não é nova.
        val seen = homeSnap(layout, true, "", listOf(AddonEntry(a)), listOf(col, emptyCol), seen = setOf(prog.key, cat.key))
        assertEquals(2, seen.fresh)
        assertEquals(0, homeSnap(layout, true, "", listOf(AddonEntry(a)), listOf(col), seen = null).fresh)
        // Nome do destaque escolhido.
        assertEquals("Em alta", homeSnap(listOf(HomeRowPref(SystemRow.TRENDING_MOVIES.key, title = "Em alta")), true, SystemRow.TRENDING_MOVIES.key, emptyList(), emptyList()).hero)
    }

    @Test
    fun `resumo das contas`() {
        assertEquals("Não conectado" to null, accountShort(Account.NUVIO, AccountSnap(), null, null, now, utc))
        assertEquals("Entre de novo" to TagTone.WARN, accountShort(Account.NUVIO, AccountSnap(lost = true), null, null, now, utc))
        assertEquals("Conectado" to TagTone.ON, accountShort(Account.NUVIO, signed, null, null, now, utc))
        assertEquals("Trazendo" to null, accountShort(Account.NUVIO, signed, null, ImportRun(ImportPart.entries), now, utc))
        assertEquals("Trazido hoje 14h32" to TagTone.ON, accountShort(Account.NUVIO, signed, allOk, null, now, utc))
        val bad = parts("addons" to PartRecord(false, why = "x"), "library" to PartRecord(true))
        assertEquals("Faltou parte" to TagTone.WARN, accountShort(Account.NUVIO, signed, bad, null, now, utc))
        val none = parts("addons" to PartRecord(false, why = "x"), "library" to PartRecord(false, why = "y"))
        assertEquals("Não veio" to TagTone.BAD, accountShort(Account.NUVIO, signed, none, null, now, utc))
    }

    @Test
    fun `nenhum texto novo usa travessao ponto e virgula ou dois pontos`() {
        val strings = buildList {
            listOf(Account.NUVIO, Account.STREMIO).forEach { a ->
                add(a.hint); addAll(ImportPart.entries.flatMap { listOf(it.label, it.line, it.what, it.the, it.miss) })
                listOf(AccountSnap(), AccountSnap(lost = true), signed, signed.copy(since = false)).forEach { s ->
                    listOf(null, allOk, parts("addons" to PartRecord(false, why = "x")), parts("addons" to PartRecord(false, why = "x"), "library" to PartRecord(true))).forEach { e ->
                        val v = accountView(a, s, e, null, now, utc)
                        addAll(v.lines); addAll(v.acts.map { it.label }); v.tag?.let { add(it.text) }
                    }
                }
            }
            addAll(homeView(HomeSnap(23, 16, 3, true, "Tops"), false).lines)
            addAll(addonsView(AddonsSnap(7, 6, 16), CatalogState()).lines)
            addAll(collectionsView(ColsSnap(3, 14), CatalogState(nuvio = signed)).lines)
            addAll(kidsView().lines)
            ImportPart.entries.forEach { p -> add(partSummary(p, PartRecord(true, n = 3, skip = 1, list = 4, progress = 2, label = "Real-Debrid", rows = 12, rowsOn = 10)).let { it.text + it.sub }) }
        }
        strings.forEach { t ->
            assertFalse("travessao em: $t", t.contains('—') || t.contains('–'))
            assertFalse("ponto e virgula em: $t", t.contains(';'))
            assertFalse("dois pontos em: $t", t.contains(':'))
        }
    }
}
