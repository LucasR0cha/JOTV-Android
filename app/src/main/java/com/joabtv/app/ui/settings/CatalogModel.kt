package com.joabtv.app.ui.settings

import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.AccountSnap
import com.joabtv.app.data.accounts.ImportEntry
import com.joabtv.app.data.accounts.ImportLog
import com.joabtv.app.data.accounts.ImportPart
import com.joabtv.app.data.accounts.ImportRun
import com.joabtv.app.data.accounts.PartRecord
import com.joabtv.app.data.collections.MediaCollection
import com.joabtv.app.data.local.AddonEntry
import com.joabtv.app.data.local.HomeLayout
import com.joabtv.app.data.local.HomeRowPref
import com.joabtv.app.data.local.SystemRow
import com.joabtv.app.data.remote.AddonLinks
import com.joabtv.app.ui.collections.countOf
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// Texto e estado da seção "Seu catálogo". Tudo aqui é função pura (sem Compose), para a regra dos
// cartões ficar testável. A tela só desenha o CardView que sai daqui.

enum class TagTone { ON, WARN, BAD }

data class CardTag(val text: String, val tone: TagTone)

/** Botão de um cartão. [id] fica igual entre os estados, para o foco voltar ao mesmo botão. */
data class CardAct(val id: String, val label: String, val busy: Boolean = false, val blocked: Boolean = false)

/** Um cartão vivo. O primeiro botão de [acts] é o primário. */
data class CardView(
    val key: String,
    val title: String,
    val mark: String? = null,
    val tag: CardTag? = null,
    val lines: List<String>,
    val acts: List<CardAct>,
    /** Conta conectada e nada trazido neste perfil. Leva o foco inicial. */
    val pending: Boolean = false,
    /** A última importação perdeu alguma parte. */
    val failed: Boolean = false,
)

data class HomeSnap(
    val rows: Int = 0,
    val addonRows: Int = 0,
    val colRows: Int = 0,
    val catalogOn: Boolean = true,
    /** Nome do destaque escolhido. Vazio é automático. */
    val hero: String = "",
    /** Addon que trouxe fileiras demais (nome e quantas aparecem). */
    val big: Pair<String, Int>? = null,
    /** Fileiras que entraram desde a última visita à Minha Início. */
    val fresh: Int = 0,
)

data class AddonsSnap(val total: Int = 0, val active: Int = 0, val rows: Int = 0)

data class ColsSnap(val count: Int = 0, val folders: Int = 0)

/**
 * Quantas fileiras aparecem de fato na Início. Fileira ligada mas bloqueada (sugestões desligadas, addon
 * desligado, coleção sem pasta) não conta. [heroKey] vazio é o destaque automático. [seen] e [keep] são o que
 * o perfil já viu em Minha Início e os addons grandes que ele já decidiu manter.
 */
fun homeSnap(
    layout: List<HomeRowPref>,
    catalogOn: Boolean,
    heroKey: String,
    addons: List<AddonEntry>,
    cols: List<MediaCollection>,
    seen: Set<String>? = null,
    keep: Set<String> = emptySet(),
): HomeSnap {
    val addonOn = addons.associate { AddonLinks.baseOf(it.url) to it.enabled }
    val colById = cols.associateBy { HomeLayout.COL_PREFIX + it.id }
    val shown = layout.filter { r ->
        val ref = r.ref
        r.enabled && when {
            r.system?.catalog == true -> catalogOn
            ref != null -> addonOn[ref.base] != false
            r.collectionId != null -> colById[r.key]?.folders?.isNotEmpty() == true
            else -> true
        }
    }
    return HomeSnap(
        rows = shown.size,
        addonRows = shown.count { it.ref != null },
        colRows = shown.count { it.collectionId != null },
        catalogOn = catalogOn,
        hero = layout.firstOrNull { it.key == heroKey }?.displayTitle.orEmpty(),
        big = HomeLayout.bigAddon(layout, addonOn.keys, keep)?.let { it.name to it.n },
        fresh = HomeLayout.fresh(seen, shown.map { it.key }).size,
    )
}

/** Opções do destaque da Início. Trailers, Gêneros, Novos episódios e coleções não servem (não são cartões de título). */
fun heroChoicesOf(layout: List<HomeRowPref>): List<Pair<String, String>> =
    layout.filter {
        it.enabled && it.key != SystemRow.TRAILERS.key && it.key != SystemRow.GENRES.key &&
            it.key != SystemRow.NEW_EPISODES.key && it.collectionId == null
    }.map { it.key to it.displayTitle }

/** Tudo que os cinco cartões e o resumo leem. */
data class CatalogState(
    val nuvio: AccountSnap = AccountSnap(),
    val stremio: AccountSnap = AccountSnap(),
    val log: ImportLog = ImportLog(),
    val running: Map<String, ImportRun> = emptyMap(),
    val home: HomeSnap = HomeSnap(),
    val addons: AddonsSnap = AddonsSnap(),
    val cols: ColsSnap = ColsSnap(),
    val profileName: String = "",
    val kids: Boolean = false,
) {
    fun snap(a: Account) = if (a == Account.NUVIO) nuvio else stremio
}

// ── Datas ───────────────────────────────────────────────────────────────────

private fun cal(ms: Long, tz: TimeZone) = Calendar.getInstance(tz).apply { timeInMillis = ms }

private fun dayStart(ms: Long, tz: TimeZone): Long = cal(ms, tz).apply {
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis

/** "14h32". */
fun hhmm(at: Long, tz: TimeZone = TimeZone.getDefault()): String =
    cal(at, tz).let { String.format(Locale.ROOT, "%02dh%02d", it.get(Calendar.HOUR_OF_DAY), it.get(Calendar.MINUTE)) }

private fun ddmm(at: Long, tz: TimeZone): String =
    cal(at, tz).let { String.format(Locale.ROOT, "%02d/%02d", it.get(Calendar.DAY_OF_MONTH), it.get(Calendar.MONTH) + 1) }

private fun daysAgo(at: Long, now: Long, tz: TimeZone) = ((dayStart(now, tz) - dayStart(at, tz)) / 86_400_000.0).roundToInt()

/** "hoje às 14h32", "ontem às 14h32" e depois "em 08/11 às 14h32". */
fun whenText(at: Long, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): String {
    val hm = hhmm(at, tz)
    val d = daysAgo(at, now, tz)
    return if (d <= 0) "hoje às $hm" else if (d == 1) "ontem às $hm" else "em ${ddmm(at, tz)} às $hm"
}

/** Versão curta para o resumo, "hoje 14h32", "ontem 14h32" ou "08/11". */
fun whenShort(at: Long, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): String {
    val d = daysAgo(at, now, tz)
    return if (d <= 0) "hoje ${hhmm(at, tz)}" else if (d == 1) "ontem ${hhmm(at, tz)}" else ddmm(at, tz)
}

/** 1047 vira "1.047". */
fun thousands(n: Int): String = n.toString().reversed().chunked(3).joinToString(".").reversed()

// ── O que veio ──────────────────────────────────────────────────────────────

enum class Mark { OK, BAD, NONE }

/** Uma parte trazida em duas linhas. [mark] BAD é parte que faltou, NONE é a conta não ter o que trazer (não é erro). */
data class PartSummary(val mark: Mark, val text: String, val sub: String = "")

fun partSummary(p: ImportPart, r: PartRecord): PartSummary {
    if (!r.ok) return PartSummary(Mark.BAD, p.miss, r.why)
    return when (p) {
        ImportPart.ADDONS -> {
            val total = r.n + r.skip
            if (total == 0 && r.failed == 0) {
                return PartSummary(Mark.NONE, if (r.unusable > 0) "Os addons da conta só rodam no outro app" else "A conta não tem addons")
            }
            val sub = buildList {
                if (r.rows > 0) add(HomeLayout.rowsText(minOf(r.rowsOn, r.rows), r.rows).removeSuffix("."))
                if (r.failed > 0) add(if (r.failed == 1) "1 não respondeu" else "${r.failed} não responderam")
                if (r.unusable > 0) add(if (r.unusable == 1) "1 só roda no outro app" else "${r.unusable} só rodam no outro app")
            }.joinToString(". ")
            PartSummary(Mark.OK, countOf(total, "addon", "addons") + if (r.skip > 0) ", ${r.skip} já estavam" else "", sub)
        }
        ImportPart.LIBRARY -> PartSummary(
            Mark.OK, "${r.list} na Minha Lista",
            "${r.progress} no Continuar assistindo" + if (r.nomatch > 0) ". ${r.nomatch} sem par no TMDB" else "",
        )
        ImportPart.COLLECTIONS ->
            if (r.n > 0) PartSummary(Mark.OK, countOf(r.n, "coleção", "coleções")) else PartSummary(Mark.NONE, "A conta não tem coleções")
        ImportPart.DEBRID ->
            if (r.label.isNotBlank()) PartSummary(Mark.OK, "Debrid conectado (${r.label})") else PartSummary(Mark.NONE, "A conta não tem chave de debrid")
    }
}

// ── Cartões ─────────────────────────────────────────────────────────────────

/** Estado do cartão de uma conta, conforme a matriz 4.1 da especificação. */
fun accountView(
    a: Account,
    snap: AccountSnap,
    entry: ImportEntry?,
    run: ImportRun?,
    now: Long = System.currentTimeMillis(),
    tz: TimeZone = TimeZone.getDefault(),
): CardView {
    val login = CardAct("login", "Entrar com o celular")
    val more = CardAct("more", "Mais")
    val what = CardAct("what", "O que veio")
    fun card(tag: CardTag?, lines: List<String>, acts: List<CardAct>, pending: Boolean = false, failed: Boolean = false) =
        CardView(a.key, a.label, a.mark, tag, lines, acts, pending, failed)

    if (!snap.signedIn) {
        return if (snap.lost) card(
            CardTag("Entre de novo", TagTone.WARN),
            listOf("A conexão com o ${a.label} venceu. O que já veio continua aqui."),
            listOf(login, more),
        ) else card(null, listOf(a.hint), listOf(login, CardAct("email", "Usar e-mail e senha")))
    }
    val on = CardTag("Conectado", TagTone.ON)
    val who = snap.email.ifBlank { "Conta conectada" }
    if (run != null) {
        return card(on, listOf(who, run.text), listOf(CardAct("all", "Trazendo…", busy = true), CardAct("more", "Mais", blocked = true)))
    }
    if (entry == null) {
        return card(
            on,
            listOf(who + if (snap.since) ". Ainda não trouxe nada para este perfil." else ". Trazer de novo só soma o que faltar."),
            listOf(CardAct("all", "Trazer tudo"), more),
            pending = snap.since,
        )
    }
    val bad = entry.failedParts
    val header = who + if (a == Account.NUVIO && entry.profile.isNotBlank()) ", perfil ${entry.profile} do Nuvio" else ""
    if (bad.size == entry.parts.size) {
        return card(
            CardTag("Não veio", TagTone.BAD),
            listOf(header, "O ${a.label} não respondeu. Confira a internet e tente de novo."),
            listOf(CardAct("all", "Tentar de novo"), more),
            failed = true,
        )
    }
    if (bad.isNotEmpty()) {
        val names = bad.map { it.the }
        val list = if (names.size > 1) names.dropLast(1).joinToString(", ") + " e " + names.last() else names[0]
        val plural = bad.size > 1 || bad[0].many
        return card(
            CardTag("Faltou parte", TagTone.WARN),
            listOf(header, "Trazido ${whenText(entry.at, now, tz)}, mas $list " + if (plural) "não vieram." else "não veio."),
            listOf(CardAct("retry", "Tentar o que faltou"), what, more),
            failed = true,
        )
    }
    val got = buildList {
        entry.part(ImportPart.ADDONS)?.let { add(countOf(it.n + it.skip, "addon", "addons")) }
        entry.part(ImportPart.LIBRARY)?.let { add("${it.list} na Minha Lista") }
        entry.part(ImportPart.COLLECTIONS)?.let { add(countOf(it.n, "coleção", "coleções")) }
    }
    return card(
        on,
        listOf(header, "Trazido ${whenText(entry.at, now, tz)}." + if (got.isNotEmpty()) " ${got.joinToString(", ")}." else ""),
        listOf(CardAct("all", "Atualizar"), what, more),
    )
}

fun homeView(h: HomeSnap, importing: Boolean): CardView {
    val line = when {
        importing -> "Chegando fileiras novas…"
        h.big != null -> "${thousands(h.rows)} fileiras. ${h.big.first} trouxe muitas."
        h.addonRows == 0 && h.colRows == 0 ->
            if (h.catalogOn) countOf(h.rows, "fileira", "fileiras") + " das sugestões do JOTV." else "Só seu progresso. Ligue as sugestões ou instale um addon."
        h.fresh > 0 -> countOf(h.rows, "fileira", "fileiras") + ", " + countOf(h.fresh, "nova", "novas") + "."
        else -> countOf(h.rows, "fileira", "fileiras") + ", ${h.addonRows} de addons" +
            (if (h.colRows > 0) " e ${h.colRows} de coleções" else "") + "." +
            (if (h.hero.isNotBlank()) " Destaque ${h.hero}." else "")
    }
    return CardView(
        "home", "Minha Início", lines = listOf(line),
        acts = listOf(CardAct("organize", "Organizar"), CardAct("hero", "Destaque " + h.hero.ifBlank { "Automático" })),
    )
}

fun addonsView(s: AddonsSnap, state: CatalogState): CardView {
    val importing = state.running.isNotEmpty()
    val missed = Account.entries.firstOrNull { a -> state.log[a]?.part(ImportPart.ADDONS)?.ok == false }
    val acct = Account.entries.firstOrNull { state.snap(it).signedIn }
    val off = s.total - s.active
    val line = when {
        importing -> "Chegando addons…"
        missed != null -> "Os addons do ${missed.label} não vieram."
        s.total == 0 ->
            if (acct != null && state.log[acct] == null) "Nenhum addon. O ${acct.label} pode ter addons seus."
            else "Nenhum addon. Eles trazem fontes para assistir e fileiras novas."
        else -> countOf(s.active, "ativo", "ativos") +
            if (off > 0) ", " + countOf(off, "desligado", "desligados") else ", " + countOf(s.rows, "fileira", "fileiras") + " na Início"
    }
    val acts = listOf(CardAct("explore", "Explorar")) +
        if (s.total > 0) listOf(CardAct("rows", "Escolher fileiras"), CardAct("manage", "Gerenciar")) else listOf(CardAct("link", "Colar link"))
    return CardView("addons", "Addons", lines = listOf(line), acts = acts)
}

/** O botão da conta segue o estado do Nuvio (Entrar, Trazer, Atualizar ou Tentar de novo). */
fun collectionsView(c: ColsSnap, state: CatalogState, linkBusy: Boolean = false): CardView {
    val nv = state.nuvio.signedIn
    val r = state.log[Account.NUVIO]?.part(ImportPart.COLLECTIONS)
    val link = CardAct("link", if (linkBusy) "Trazendo…" else "Abrir link", busy = linkBusy)
    fun pull(label: String) = CardAct("pull", label)
    val run = state.running[Account.NUVIO.key]
    if (run != null && ImportPart.COLLECTIONS in run.parts) {
        return CardView("cols", "Coleções", lines = listOf("Chegando coleções…"), acts = listOf(CardAct("pull", "Trazendo…", busy = true), link))
    }
    val (line, acts) = when {
        nv && r != null && !r.ok -> "As coleções do Nuvio não vieram." to listOf(pull("Tentar de novo"), link)
        c.count > 0 -> (countOf(c.count, "coleção", "coleções") + " com " + countOf(c.folders, "pasta", "pastas") + if (r != null && r.ok && r.n > 0) ", do Nuvio" else "") to
            listOf(CardAct("open", "Ver coleções"), if (nv) pull("Atualizar") else link)
        // Só diz que o Nuvio não tem coleções quando a última vez ele não trouxe nenhuma. Se trouxe e a pessoa apagou, ele pode ter.
        nv -> (if (r != null && r.ok && r.n == 0) "O Nuvio não tem coleções neste perfil. Traga de um link ou crie no celular." else "O Nuvio pode ter coleções suas.") to
            listOf(pull("Trazer do Nuvio"), link)
        else -> "Fileiras com pastas, como Marvel ou Anime. Nenhuma ainda." to listOf(CardAct("login", "Entrar no Nuvio"), link)
    }
    return CardView("cols", "Coleções", lines = listOf(line), acts = acts)
}

/** Perfil infantil. Só sugestões do JOTV, então os outros cartões nem aparecem. */
fun kidsView() = CardView(
    "kids", "Início do perfil infantil",
    lines = listOf("Só sugestões do JOTV para a idade. Addons e coleções ficam nos perfis de adulto."),
    acts = listOf(CardAct("profiles", "Trocar de perfil")),
)

/** Os cartões na ordem fixa. A ordem não muda com o estado, para o controle achar o mesmo item com as mesmas setas. */
fun catalogCards(s: CatalogState, linkBusy: Boolean = false, now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault()): List<CardView> {
    if (s.kids) return listOf(kidsView())
    val importing = s.running.isNotEmpty()
    return listOf(
        homeView(s.home, importing),
        accountView(Account.NUVIO, s.nuvio, s.log[Account.NUVIO], s.running[Account.NUVIO.key], now, tz),
        accountView(Account.STREMIO, s.stremio, s.log[Account.STREMIO], s.running[Account.STREMIO.key], now, tz),
        addonsView(s.addons, s),
        collectionsView(s.cols, s, linkBusy),
    )
}

/**
 * "cartão/botão" que recebe o foco ao abrir as Configurações, na primeira regra que valer.
 * 1. Volta de uma tela aberta pelo cartão, [opened]. Importação em andamento, o botão travado.
 * 2. Conta conectada sem nada trazido neste perfil. 3. Última importação com falha. 4. Organizar, de Minha Início.
 */
fun firstFocusKey(cards: List<CardView>, opened: String = ""): String {
    if (opened.isNotEmpty()) return opened
    cards.firstOrNull { it.acts.firstOrNull()?.busy == true }?.let { return "${it.key}/${it.acts[0].id}" }
    cards.firstOrNull { it.pending }?.let { return "${it.key}/all" }
    cards.firstOrNull { it.failed }?.let { c -> return "${c.key}/" + (c.acts.firstOrNull { it.id == "retry" } ?: c.acts.first()).id }
    return cards.firstOrNull { it.key == "home" }?.let { "home/organize" } ?: cards.first().let { "${it.key}/${it.acts.first().id}" }
}

/** Resumo de uma conta para a coluna da direita, com o tom para a cor. */
fun accountShort(
    a: Account, snap: AccountSnap, entry: ImportEntry?, run: ImportRun?,
    now: Long = System.currentTimeMillis(), tz: TimeZone = TimeZone.getDefault(),
): Pair<String, TagTone?> = when {
    !snap.signedIn -> if (snap.lost) "Entre de novo" to TagTone.WARN else "Não conectado" to null
    run != null -> "Trazendo" to null
    entry == null -> "Conectado" to TagTone.ON
    entry.failedParts.size == entry.parts.size -> "Não veio" to TagTone.BAD
    entry.failedParts.isNotEmpty() -> "Faltou parte" to TagTone.WARN
    else -> "Trazido ${whenShort(entry.at, now, tz)}" to TagTone.ON
}
