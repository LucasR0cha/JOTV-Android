package com.joabtv.app.data.local

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PlaybackVisibilityTest {
    @Test fun remocaoContinuaValendoAoImportarBackupAntigo() {
        val key = playbackKey(1399, "SERIES")
        val removed = hidePlayback("{}", key, 2000)
        val older = hidePlayback("{}", key, 1000)
        assertEquals(2000L, playbackHiddenBefore(mergePlaybackVisibility(removed, older), key))
        assertEquals(2000L, playbackHiddenBefore(mergePlaybackVisibility(older, removed), key))
        assertEquals(key, playbackKey(1399, "tv"))
        assertEquals(0L, playbackHiddenBefore(removed, playbackKey(1399, "MOVIE")))
    }

    @Test fun retomarTituloSoRemoveSuaOcultacao() {
        val movie = playbackKey(12, "MOVIE")
        val series = playbackKey(1399, "SERIES")
        val hidden = hidePlayback(hidePlayback("{}", movie, 2000), series, 3000)
        val played = unhidePlayback(hidden, movie)
        assertEquals(0L, playbackHiddenBefore(played, movie))
        assertEquals(3000L, playbackHiddenBefore(played, series))
        val cleared = JSONObject().put("allBefore", 4000).toString()
        assertEquals(4000L, playbackHiddenBefore(mergePlaybackVisibility(played, cleared), movie))
    }
}
