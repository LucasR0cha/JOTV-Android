package com.joabtv.app.ui.movies

import com.joabtv.app.domain.model.EpisodeItem

internal suspend fun collectEpisodeWatchRange(
    target: EpisodeItem,
    previousOnly: Boolean,
    loadSeason: suspend (Int) -> List<EpisodeItem>,
): List<Pair<Int, Int>> {
    require(target.seasonNumber in 1..200 && target.episodeNumber > 0)
    val seasons = if (previousOnly) 1..target.seasonNumber else target.seasonNumber..target.seasonNumber
    val selected = mutableListOf<Pair<Int, Int>>()
    for (number in seasons) {
        selected += loadSeason(number).filter { ep ->
            ep.seasonNumber == number && ep.episodeNumber > 0 &&
                (!previousOnly || number < target.seasonNumber || ep.episodeNumber < target.episodeNumber)
        }.map { it.seasonNumber to it.episodeNumber }
    }
    return selected.distinct()
}
