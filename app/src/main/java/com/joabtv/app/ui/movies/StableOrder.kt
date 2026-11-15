package com.joabtv.app.ui.movies

import com.joabtv.app.data.remote.StremioStream
import java.util.IdentityHashMap

/** Um cartão na tela. [id] não muda enquanto o cartão existir e [lead] é a fonte que ele toca. */
internal class ShownRelease(val id: String, val group: ReleaseGroup, val lead: StremioStream)

/** Cartões na ordem da tela e quantas versões ficaram atrás de Mostrar mais. */
internal class StablePage(val list: List<ShownRelease>, val rest: Int)

/**
 * Ordem das versões na tela enquanto os addons ainda respondem (igual ao stableOrder do webOS).
 * A ordem de prioridade muda a cada resposta, e uma fonte lenta de addon prioritário empurrava tudo
 * para baixo com o dedo (ou o foco) já no cartão. Aqui o cartão que apareceu não sai do lugar por
 * causa de resposta nova, de teste da fonte ou de ajuste de preferência. O que chega depois entra
 * embaixo. Quando a busca termina, se a pessoa ainda não mexeu na lista, a ordem de prioridade vale
 * numa vez só. Uma instância por lista. Filtro e recarga chamam [reset], Mostrar mais muda o limit.
 */
internal class StableOrder {
    private var shown = listOf<String>()
    private val idOf = HashMap<String, String>()
    private val leadUrl = HashMap<String, String>()
    private var settled = false

    /** A pessoa mexeu na lista (toque ou foco). Dali em diante a ordem que ela viu não muda sozinha. */
    var touched = false
        private set

    /** Ainda falta aplicar a ordem de prioridade do fim da busca. */
    val waiting get() = !settled

    /**
     * Cartão que abriu a lista no fim da busca e leva o rótulo Melhor opção. Decidido uma vez só,
     * e só quando a pessoa não tinha mexido. Acompanhar a melhor de cada momento fazia o destaque
     * pular de cartão a cada teste de fonte.
     */
    var featured: String? = null
        private set

    fun touch() { touched = true }

    fun reset() {
        shown = emptyList(); idOf.clear(); leadUrl.clear(); touched = false; settled = false; featured = null
    }

    /**
     * [groups] vem na ordem ideal (prioridade dos addons), já filtrado pelo que a pessoa pediu.
     * [loading] diz que ainda há addon respondendo, [busy] que a pessoa está mexendo agora, [hide]
     * que o grupo é de outro filme, [off] que a fonte morreu ou é de outro filme (não toca por ela
     * se houver outra) e [first] escolhe o grupo que abre a lista no fim.
     */
    fun update(
        groups: List<ReleaseGroup>,
        limit: Int = 24,
        loading: Boolean = false,
        busy: Boolean = false,
        hide: (ReleaseGroup) -> Boolean = { false },
        off: (StremioStream) -> Boolean = { false },
        first: ((ReleaseGroup) -> Boolean)? = null,
    ): StablePage {
        val byId = HashMap<String, ReleaseGroup>()
        val idByGroup = IdentityHashMap<ReleaseGroup, String>()
        groups.forEachIndexed { i, g ->
            val keys = g.keys
            var id = keys.firstNotNullOfOrNull { idOf[it] } ?: g.key
            if (id in byId) id = "${g.key}~$i" // dois grupos nunca dividem a identidade
            keys.forEach { idOf.putIfAbsent(it, id) }
            byId[id] = g
            idByGroup[g] = id
        }
        val onScreen = shown.toHashSet()
        // Quem já está na tela fica, mesmo se virar de outro filme. Quem chega agora só entra se não for.
        val eligible = groups.filter { idByGroup[it] in onScreen || !hide(it) }
        val kept = shown.filter { it in byId }
        val have = kept.toHashSet()
        val fresh = pageOf(eligible, limit).map { idByGroup.getValue(it) }.filter { it !in have }
        // Enquanto chega, a lista pode passar da página, mas só até o dobro.
        var next = kept + fresh.take(maxOf(0, limit * 2 - kept.size))
        if (!loading && !settled) {
            if (touched) settled = true
            else if (!busy) {
                settled = true
                var final = pageOf(groups.filterNot(hide), limit)
                val front = first?.let { f -> final.firstOrNull(f) }
                if (front != null) final = listOf(front) + final.filter { it !== front }
                featured = front?.let { idByGroup[it] }
                next = final.map { idByGroup.getValue(it) }
            }
        }
        shown = next
        val list = next.map { id ->
            val g = byId.getValue(id)
            val lead = pickLead(id, g, off)
            leadUrl[id] = lead.url
            ShownRelease(id, g, lead)
        }
        return StablePage(list, maxOf(0, eligible.size - list.size))
    }

    /** A fonte do cartão fica a mesma. Só troca quando ela morre ou é de outro filme. */
    private fun pickLead(id: String, g: ReleaseGroup, off: (StremioStream) -> Boolean): StremioStream {
        val url = leadUrl[id]
        return g.members.firstOrNull { it.url == url && !off(it) }
            ?: g.members.firstOrNull { !off(it) }
            ?: g.lead
    }
}

/** O mesmo para as pílulas de filtro. Ficam na ordem em que apareceram e arrumam no fim da busca. */
internal class StableKeys {
    private var shown = listOf<String>()
    private var touched = false
    private var settled = false

    val waiting get() = !settled

    fun touch() { touched = true }

    fun reset() { shown = emptyList(); touched = false; settled = false }

    fun update(keys: List<String>, loading: Boolean = false, busy: Boolean = false): List<String> {
        val present = keys.toHashSet()
        val kept = shown.filter { it in present }
        val have = kept.toHashSet()
        var next = kept + keys.filter { it !in have }
        if (!loading && !settled) {
            if (touched) settled = true
            else if (!busy) { settled = true; next = keys }
        }
        shown = next
        return next
    }
}
