package com.joabtv.app.ui.settings

import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.BatchImport
import com.joabtv.app.data.accounts.BatchItem
import com.joabtv.app.data.accounts.ImportPart
import com.joabtv.app.data.accounts.Load
import com.joabtv.app.data.local.ProfileCategory
import com.joabtv.app.ui.collections.countOf

// Texto e regras das telas de perfis independentes e da importação para outros perfis. Função pura, sem Compose,
// com os mesmos textos da versão web (screens/profiles.js e screens/settings.js).

/** Nome da categoria nas telas. */
fun categoryLabel(c: ProfileCategory): String = when (c) {
    ProfileCategory.ADDONS -> "Addons"
    ProfileCategory.PLUGINS -> "Plugins"
    ProfileCategory.CATALOG -> "Catálogo e Início"
    ProfileCategory.LIVE -> "TV ao vivo"
    ProfileCategory.DEBRID -> "Debrid"
}

/** Categorias que dá para copiar ou usar num perfil. Perfil infantil não leva plugins. */
fun copyCategories(kids: Boolean): List<ProfileCategory> =
    ProfileCategory.entries.filter { !(kids && it == ProfileCategory.PLUGINS) }

/** O que vem marcado. O debrid fica de fora porque leva as chaves. */
fun defaultCopySelection(kids: Boolean): Set<ProfileCategory> = copyCategories(kids).filter { it != ProfileCategory.DEBRID }.toSet()

/** Linha de baixo de uma categoria em Fontes e catálogo. [ownerName] null quando os dados são do próprio perfil. */
fun categoryLine(ownerName: String?, tv: Boolean): String =
    if (ownerName == null) "Próprios deste perfil"
    else "Usando os de $ownerName. Mudar vale para os dois. ${if (tv) "OK" else "Toque e"} deixa de usar."

/** "Ana", "Ana e Bia", "Ana, Bia e Caio". */
fun joinNames(names: List<String>): String =
    if (names.size <= 1) names.firstOrNull().orEmpty() else names.dropLast(1).joinToString(", ") + " e " + names.last()

/** Aviso antes de remover um perfil. [users] são os perfis que usam dados dele e ficam com uma cópia. */
fun removeProfileBody(users: List<String>): String =
    "Minha Lista, histórico, addons e plugins desse perfil somem." +
        if (users.isEmpty()) "" else " ${joinNames(users)} ${if (users.size == 1) "fica" else "ficam"} com uma cópia do que usava dele."

/**
 * Trazer tudo do cartão. Abre a escolha quando há o que escolher (a conta da Nuvio tem mais de um perfil, ou o aparelho
 * tem mais de um perfil). Sem nada para escolher traz direto, em um clique. null quer dizer que ainda falta a lista de
 * perfis da Nuvio para decidir.
 */
fun importNeedsChoice(a: Account, jotvProfiles: Int, nuvioState: Load, nuvioProfiles: Int): Boolean? = when {
    jotvProfiles > 1 -> true
    a != Account.NUVIO -> false
    nuvioState == Load.OK -> nuvioProfiles > 1
    nuvioState == Load.FAIL -> false
    else -> null
}

/** Rótulo do botão que traz. [all] é o chip Todos os perfis. */
fun importGoLabel(chosen: List<ImportPart>, total: Int, all: Boolean): String = when {
    all && chosen.size == total -> "Trazer todos os perfis"
    chosen.size == total -> "Trazer tudo"
    chosen.size == 1 -> "Trazer ${chosen[0].what}"
    else -> "Trazer ${chosen.size} de $total"
}

/** "Perfil 2 de 3, Bia" enquanto o Trazer todos roda. null fora dele. */
fun batchStepText(b: BatchImport?): String? {
    if (b == null || !b.running || b.items.size < 2) return null
    val i = b.items.indexOfFirst { it.state == com.joabtv.app.data.accounts.BatchState.RUNNING }
    if (i < 0) return null
    return "Perfil ${i + 1} de ${b.items.size}, ${b.items[i].nuvioName.ifBlank { b.items[i].profileName }}"
}

/** Texto de cima do resumo do Trazer todos. */
fun batchSummaryBody(b: BatchImport): String {
    if (b.error.isNotBlank()) return "Parou no meio. ${b.error.trimEnd('.')}. O que já veio ficou nos perfis."
    val made = b.items.size
    return (if (made == 1) "1 perfil trazido" else "$made perfis trazidos") + ". Cada um ficou com os próprios addons e plugins." +
        if (b.leftOut > 0) " " + countOf(b.leftOut, "perfil ficou de fora", "perfis ficaram de fora") + " pelo limite de perfis." else ""
}

/** Linha de um perfil no resumo. O segundo valor diz se faltou alguma parte. */
fun batchItemLine(it: BatchItem): Pair<String, Boolean> {
    val r = it.results
    val miss = r.filterValues { !it.ok }.keys
    if (miss.isNotEmpty()) return "Faltou " + miss.joinToString(", ") { p -> p.what } to true
    val got = buildList {
        r[ImportPart.ADDONS]?.let { add(countOf(it.n + it.skip, "addon", "addons")) }
        r[ImportPart.PLUGINS]?.takeIf { it.n + it.skip > 0 }?.let { add(countOf(it.n + it.skip, "repositório de plugins", "repositórios de plugins")) }
        r[ImportPart.LIBRARY]?.let { add("${it.list} na Minha Lista") }
    }
    return got.joinToString(", ") to false
}
