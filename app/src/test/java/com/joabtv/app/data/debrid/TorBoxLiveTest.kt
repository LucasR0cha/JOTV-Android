package com.joabtv.app.data.debrid

import com.joabtv.app.data.torrent.TorrentSpec
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Ensaio do cliente Kotlin do TorBox contra o serviço de verdade. Só roda com a variável TORBOX_API_KEY definida
 * (a chave nunca é gravada) e é pulado sem ela. Não cria torrent novo, porque um envio novo, até de torrent em
 * cache, empurra o cooldown_until da conta em 24 h. Usa um torrent pequeno que já está na conta (o TorBox devolve o
 * mesmo id) e um hash inventado, que tem de ser recusado. No fim a conta tem de estar igual.
 * Mesmo contrato que tools/torbox-live-check.cjs confere para o código da web.
 */
class TorBoxLiveTest {
    private val key = System.getenv("TORBOX_API_KEY").orEmpty()

    @Test
    fun `contrato real do torbox sem criar torrent`() {
        assumeTrue("sem TORBOX_API_KEY o ensaio ao vivo é pulado", key.isNotBlank())
        val http = DebridHttp(OkHttpClient())
        val api = TorBoxApi(http)
        val base = TorBoxApi.BASE

        fun torrents() = JSONObject(http.get("$base/torrents/mylist?bypass_cache=true&limit=1000", key).body).optJSONArray("data")
        fun cooldown() = JSONObject(http.get("$base/user/me", key).body).optJSONObject("data")?.optString("cooldown_until").orEmpty()

        val list = requireNotNull(torrents()) { "a conta não devolveu a lista de torrents" }
        val before = list.length()
        val cooldownBefore = cooldown()
        val cached = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optBoolean("cached") && it.optLong("size") > 0 }.sortedBy { it.optLong("size") }
        assumeTrue("a conta não tem torrent em cache para o ensaio", cached.isNotEmpty())
        val small = cached.firstOrNull { it.optLong("size") > 50_000_000L } ?: cached.first()
        val hash = small.getString("hash").lowercase()
        val fake = Random.nextBytes(20).joinToString("") { "%02x".format(it) }

        // Consulta em lote. Só o hash que está em cache volta.
        assertEquals(setOf(hash), api.cached(key, listOf(hash, fake)))

        // Tocar o torrent que já existe devolve o link de download (https), sem criar nada.
        val played = api.resolve(key, "magnet:?xt=urn:btih:$hash", TorrentSpec(hash, emptyList()), 0, 0)
        // A mensagem não leva a URL, que pode trazer token de download.
        assertTrue("tocar o torrent em cache devolve o link (veio ${played::class.simpleName})", played is DebridResolve.Ok && played.url.startsWith("https://"))

        // Hash sem cache é recusado (add_only_if_cached) e o app entende como NotCached.
        assertEquals(DebridResolve.NotCached, api.resolve(key, "magnet:?xt=urn:btih:$fake", TorrentSpec(fake, emptyList()), 0, 0))

        // Chave inventada não vale.
        assertNull(api.user("00000000-0000-4000-8000-000000000000"))

        assertEquals("a conta tem de ficar com o mesmo número de torrents", before, torrents()?.length())
        assertEquals("o cooldown_until não pode mudar, nenhum envio novo foi feito", cooldownBefore, cooldown())
    }
}
