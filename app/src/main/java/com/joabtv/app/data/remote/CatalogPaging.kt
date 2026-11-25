package com.joabtv.app.data.remote

/**
 * Paginação dos catálogos de addon. No protocolo do Stremio o skip é o quanto já foi lido e o addon não diz
 * o tamanho da página, então a página seguinte existe enquanto a atual trouxer título que ainda não veio.
 * Esperar página de 40 parava a fileira na primeira página dos addons que mandam 20 ou 30.
 */
object CatalogPaging {
    /** Quantos títulos entram por vez (cada um pede o id no TMDB). */
    const val PAGE = 40

    /** Páginas seguidas sem nenhum título aproveitável (sem pôster ou id que o TMDB não conhece) até desistir. */
    const val MAX_EMPTY = 3

    /** Onde a leitura de um catálogo está. [skip] é a posição do próximo pedido e [seen] os ids já recebidos. */
    data class Cursor(
        val skip: Int = 0,
        val seen: Set<String> = emptySet(),
        val empty: Int = 0,
        val more: Boolean = true,
    )

    /** O que desta página sobe para o resolver de ids. O resto o addon manda de novo no skip seguinte. */
    fun take(metas: List<AddonMeta>): List<AddonMeta> = metas.take(PAGE)

    /**
     * Anda o cursor depois de uma página. [metas] nulo é falha de rede e o cursor fica onde estava, para a
     * rolagem tentar de novo. [resolved] é quantos títulos da página viraram cartão.
     */
    fun advance(c: Cursor, metas: List<AddonMeta>?, taken: List<AddonMeta>, resolved: Int): Cursor {
        if (metas == null) return c
        val fresh = taken.any { it.id !in c.seen }
        val empty = if (resolved == 0) c.empty + 1 else 0
        return Cursor(
            skip = c.skip + taken.size,
            seen = c.seen + taken.map { it.id },
            empty = empty,
            more = taken.isNotEmpty() && fresh && empty < MAX_EMPTY,
        )
    }
}
