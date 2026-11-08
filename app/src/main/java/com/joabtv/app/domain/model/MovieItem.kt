package com.joabtv.app.domain.model

data class MovieItem(
    val tmdbId: Int,
    val embedId: String,
    val title: String,
    val overview: String,
    val posterUrl: String,
    val backdropUrl: String,
    val year: String,
    val rating: Double,
    val genres: List<String> = emptyList(),
    val type: MediaType = MediaType.MOVIE,
    // Classificação indicativa (ex.: "L", "10", "12", "14", "16", "18"). Vazio = sem dado.
    val certification: String = "",
    // Data completa de lançamento (yyyy-MM-dd) — p/ mostrar no detalhe. Vazio no catálogo.
    val releaseDate: String = "",
)

enum class MediaType { MOVIE, SERIES }
