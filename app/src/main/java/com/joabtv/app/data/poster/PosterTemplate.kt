package com.joabtv.app.data.poster

import com.joabtv.app.domain.model.MediaType
import com.joabtv.app.domain.model.MovieItem

/**
 * Pôster por modelo de URL, no formato do Nuvio (PostersPlus e serviços parecidos).
 * O modelo só vale na hora de desenhar. Nunca vai para o item, para a Minha Lista nem para o
 * histórico, porque costuma levar chaves de API escritas por extenso.
 * O contrato é o mesmo do web e do Roku (os casos de teste são os mesmos nas três versões).
 */
object PosterTemplate {
    /** Dados do título que alimentam os marcadores do modelo. */
    data class Ctx(
        val tmdbId: String?,
        val imdbId: String?,
        val id: String?,
        val type: String?,
        /** poster, landscape ou square. */
        val shape: String,
    )

    /** Modelo do servidor público do PostersPlus, sem chave nenhuma. Serve de ponto de partida. */
    const val PRESET_PUBLIC = "https://postersplus.cc/poster?tmdb_id={tmdb_id?}&imdb_id={imdb_id?}&stremio_id={id}&type={type}&shape={shape}"

    private val VALID = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)

    /** Modelo que dá para guardar. Link http ou https sem espaço, ou vazio, que desliga. */
    fun isValid(template: String): Boolean = template.trim().let { it.isEmpty() || VALID.matches(it) }

    private val IMDB = Regex("^tt\\d+")
    private val marker = Regex("\\{([A-Za-z_]+)(\\?)?\\}")
    private val customQuery = Regex("[?&](tmdb_id|imdb_id|stremio_id|kitsu_id|anilist_id|mal_id)=", RegexOption.IGNORE_CASE)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** posterShape do addon (poster, landscape, square) para o nome que o app usa. */
    fun shapeOf(raw: String?): String = when (raw?.trim()?.lowercase()) {
        "landscape", "wide" -> "landscape"
        "square" -> "square"
        else -> "poster"
    }

    /**
     * O que o cartão desenha. [plain] diz que a arte já traz nota, selos e faixas, então o app não põe os dele.
     * Com o modelo, [fallback] é a arte própria do título, para o caso de a imagem do modelo falhar (400 do
     * quadrado, 503 de servidor ocupado ou sem rede para ele).
     */
    data class Art(val url: String?, val plain: Boolean, val fallback: String? = null)

    /** Dados do título para os marcadores do modelo. O id é o do addon, ou o do TMDB quando o título veio dele. */
    fun ctxOf(item: MovieItem, shape: String): Ctx {
        val imdb = item.imdbId.ifBlank { IMDB.find(item.sourceId)?.value ?: item.embedId.takeIf { IMDB.matches(it) } }
        return Ctx(
            tmdbId = item.tmdbId.takeIf { it > 0 }?.toString(),
            imdbId = imdb,
            id = item.sourceId.ifBlank { imdb ?: "tmdb:${item.tmdbId}" },
            type = if (item.type == MediaType.SERIES) "series" else "movie",
            shape = shape,
        )
    }

    /** Arte do cartão. Pôster que o addon já personalizou vale mais que o modelo do perfil. */
    fun artFor(item: MovieItem, template: String?, shape: String): Art {
        val own = item.posterUrl.ifBlank { item.backdropUrl }.ifBlank { null }
        val ownCustom = isCustomUrl(item.posterUrl)
        if (!ownCustom) resolve(template, ctxOf(item, shape))?.let { return Art(it, plain = true, fallback = own) }
        return Art(own, plain = ownCustom)
    }

    /** Quantas vezes a largura do retrato vale a largura de um cartão de forma [shape]. Medidas das pastas de coleção. */
    fun widthFactor(shape: String): Float = when (shape) {
        "landscape" -> 1.72f
        "square" -> 1.17f
        else -> 1f
    }

    /** Proporção largura por altura da arte de um cartão de forma [shape]. */
    fun aspectOf(shape: String): Float = when (shape) {
        "landscape" -> 16f / 9f
        "square" -> 1f
        else -> 2f / 3f
    }

    /** Com {shape} no modelo o Nuvio troca também a arte paisagem e a do Continuar assistindo. */
    fun hasShape(template: String?): Boolean =
        template != null && marker.findAll(template).any { it.groupValues[1].equals("shape", ignoreCase = true) }

    private fun typeOf(raw: String?): String? = when {
        raw.isNullOrBlank() -> null
        raw.equals("movie", ignoreCase = true) -> "movie"
        else -> "series"
    }

    /**
     * Devolve a URL pronta, ou null quando o modelo não se aplica e o cartão fica com o pôster dele.
     * Marcador obrigatório sem valor descarta a URL inteira, marcador com ponto de interrogação vira vazio.
     */
    fun resolve(template: String?, ctx: Ctx): String? {
        val t = template?.trim().orEmpty()
        if (t.isEmpty() || ctx.shape == "square") return null
        if (ctx.shape == "landscape" && !hasShape(t)) return null
        var missing = false
        val out = marker.replace(t) { m ->
            val optional = m.groupValues[2] == "?"
            val value = when (m.groupValues[1].lowercase()) {
                "tmdb_id" -> ctx.tmdbId
                "imdb_id" -> ctx.imdbId
                "id" -> ctx.id
                "type" -> typeOf(ctx.type)
                "shape" -> ctx.shape
                else -> null
            }
            if (value.isNullOrEmpty()) {
                if (!optional) missing = true
                ""
            } else {
                enc(value)
            }
        }
        if (missing) return null
        return out.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
    }

    /**
     * Verdadeiro quando o pôster já vem com nota, selos e faixas desenhados na imagem (PostersPlus,
     * RPDB). Só lista positiva, para não esconder os selos do app em arte limpa de outro servidor.
     */
    fun isCustomUrl(url: String?): Boolean {
        val u = url?.trim().orEmpty()
        if (!u.startsWith("http", ignoreCase = true)) return false
        val bare = u.substringBefore('#').substringBefore('?').substringAfter("://")
        val host = bare.substringBefore('/').lowercase()
        if (host.contains("postersplus") || host == "ratingposterdb.com" || host.endsWith(".ratingposterdb.com")) return true
        val last = bare.substringAfter('/', "").trimEnd('/').substringAfterLast('/')
        return last.equals("poster", ignoreCase = true) && customQuery.containsMatchIn(u)
    }
}
