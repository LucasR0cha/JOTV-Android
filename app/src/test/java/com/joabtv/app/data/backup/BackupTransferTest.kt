package com.joabtv.app.data.backup

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class BackupTransferTest {
    @Test fun pedidoInterrompidoNaoDerrubaAsProximasTransferencias() = runBlocking {
        val json = """{"format":"jotv.backup","version":1}"""
        val server = LanBackupServer()
        val info = server.start(json)
        try {
            java.net.Socket("127.0.0.1", info.port).use { socket ->
                socket.soTimeout = 3000
                socket.getOutputStream().apply {
                    write("GET /backup?pin=% HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
                    flush()
                }
                socket.shutdownOutput()
                assertEquals(-1, socket.getInputStream().read())
            }
            assertEquals(json, LanBackupServer().pullFrom("127.0.0.1:${info.port}", info.pin).getOrThrow())
            assertNotNull(server.info.value)
        } finally { server.stop() }
    }

    @Test fun naoAfirmaEnvioConcluidoQuandoOutroAparelhoRespondePaginaHtml() = runBlocking {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val responder = Thread {
                server.accept().use { socket ->
                    socket.soTimeout = 5000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val response = "<html>Falha</html>"
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: ${response.length}\r\nConnection: close\r\n\r\n" + response).toByteArray())
                }
            }.apply { isDaemon = true; start() }
            val result = LanBackupServer().pushTo("127.0.0.1:${server.localPort}", "1234", "{}")
            assertTrue(result.isFailure)
            responder.join(5000)
            assertFalse(responder.isAlive)
        }
    }

    @Test fun transfereArquivoSemServidorExternoEPedePin() = runBlocking {
        val json = """{"format":"jotv.backup","version":1,"profile":{"name":"Teste"},"addons":[],"history":[]}"""
        val server = LanBackupServer()
        val other = LanBackupServer()
        val info = server.start(json)
        val host = "127.0.0.1:${info.port}"
        try {
            assertEquals(json, other.pullFrom(host, info.pin).getOrThrow())
            val wrong = if (info.pin == "0000") "0001" else "0000"
            assertTrue(other.pullFrom(host, wrong).isFailure)
            val restored = JSONObject(json).put("profile", JSONObject().put("name", "Restaurado")).toString()
            other.pushTo(host, info.pin, restored).getOrThrow()
            assertEquals("Restaurado", JSONObject(server.receivedJson.value!!).getJSONObject("profile").getString("name"))
            server.clearReceived()
            assertNull(server.receivedJson.value)
            val http = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url("http://$host/").build()).execute().use {
                val html = it.body!!.string()
                assertFalse(html.contains("value=\"${info.pin}\""))
                assertFalse(html.contains("pin=${info.pin}"))
            }
            assertTrue(other.pullFrom("8.8.8.8:80", info.pin).isFailure)
            val request = Request.Builder().url("http://$host/backup?pin=${info.pin}")
                .post("{}".toRequestBody("application/json".toMediaType())).build()
            http.newCall(request).execute().use { assertEquals(400, it.code) }
            assertNull(server.receivedJson.value)
        } finally { server.stop(); other.stop() }
        assertNull(server.info.value)
    }

    @Test fun sessaoTraktETrackersSaoPortaveisEntreAndroidEWeb() {
        val android = """{"a":"token-falso","r":"refresh-falso","e":1800000000000,"u":"Teste"}"""
        val portable = portableTraktSession(android)!!
        assertEquals("token-falso", portable.getString("access_token"))
        assertEquals("token-falso", JSONObject(androidTraktSession(portable)!!).getString("a"))
        val metadata = """{"SIMKL":{"client_id":"cliente-falso","refresh_token":"refresh-falso","expires_at":1800000000000}}"""
        val trackers = portableTrackers(mapOf("simkl" to "token-falso", "mdblist" to "chave-falsa"), metadata)
        assertEquals("cliente-falso", trackers.getJSONObject("simkl").getString("client"))
        assertEquals("chave-falsa", backupTrackerToken(trackers.getJSONObject("mdblist")))
        val restored = JSONObject(androidTrackerSessions(trackers, "{}"))
        assertEquals("refresh-falso", restored.getJSONObject("SIMKL").getString("refresh_token"))
        assertEquals("legado", backupTrackerToken(JSONObject().put("apiKey", "legado")))
    }

    @Test fun mdblistPorCodigoSobreviveARestauracao() {
        val sessions = """{"MDBLIST":{"client_id":"cliente","refresh_token":"refresh-falso","expires_at":1800000000000,"oauth":true}}"""
        val trackers = portableTrackers(mapOf("mdblist" to "token-falso"), sessions)
        val item = trackers.getJSONObject("mdblist")
        assertFalse(item.has("key"))
        assertEquals("oauth", item.getString("auth"))
        assertEquals("token-falso", backupTrackerToken(item))
        val restored = JSONObject(androidTrackerSessions(trackers, "{}")).getJSONObject("MDBLIST")
        assertTrue(restored.getBoolean("oauth"))
        assertEquals("refresh-falso", restored.getString("refresh_token"))
        // Chave da API continua chave depois de restaurar.
        val key = portableTrackers(mapOf("mdblist" to "chave"), """{"MDBLIST":{"client_id":"cliente","refresh_token":"","expires_at":0,"oauth":false}}""")
        assertEquals("chave", key.getJSONObject("mdblist").getString("key"))
        assertFalse(JSONObject(androidTrackerSessions(key, "{}")).getJSONObject("MDBLIST").getBoolean("oauth"))
    }
}
