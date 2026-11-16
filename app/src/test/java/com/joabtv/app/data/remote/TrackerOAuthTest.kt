package com.joabtv.app.data.remote

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TrackerOAuthTest {
    @Test fun mdblistPedeCodigoETokenPorFormularioComBarraFinal() {
        val device = trackerDeviceForm(TrackerProvider.MDBLIST, "cliente")!!
        assertEquals("/oauth/device-authorization/", device.path)
        assertEquals(mapOf("client_id" to "cliente", "scope" to "write"), device.fields)
        val poll = trackerPollForm(TrackerProvider.MDBLIST, "cliente", "dev")!!
        assertEquals("/oauth/token/", poll.path)
        assertEquals("urn:ietf:params:oauth:grant-type:device_code", poll.fields["grant_type"])
        assertEquals("dev", poll.fields["device_code"])
        val refresh = trackerRefreshForm(TrackerProvider.MDBLIST, "cliente", "r1")!!
        assertEquals("/oauth/token/", refresh.path)
        assertEquals(mapOf("grant_type" to "refresh_token", "client_id" to "cliente", "refresh_token" to "r1"), refresh.fields)
        assertEquals("/oauth2/token", trackerPollForm(TrackerProvider.SIMKL, "c", "d")!!.path)
        assertNull(trackerDeviceForm(TrackerProvider.WETRAKR, "c"))
        assertTrue(TrackerProvider.MDBLIST.needsClientId)
    }

    @Test fun pollingLeOJsonAntesDoStatus() {
        assertEquals(DevicePoll.Pending, trackerDevicePoll(400, JSONObject().put("error", "authorization_pending")))
        assertEquals(DevicePoll.SlowDown, trackerDevicePoll(400, JSONObject().put("error", "slow_down")))
        assertEquals(DevicePoll.Token, trackerDevicePoll(200, JSONObject().put("access_token", "a").put("refresh_token", "r")))
        val denied = trackerDevicePoll(400, JSONObject().put("error", "access_denied")) as DevicePoll.Fail
        assertTrue(denied.message.contains("recusado"))
        val expired = trackerDevicePoll(400, JSONObject().put("error", "expired_token")) as DevicePoll.Fail
        assertTrue(expired.message.contains("expirou"))
        assertTrue((trackerDevicePoll(429, JSONObject()) as DevicePoll.Fail).message.startsWith("Limite"))
        // Mensagens de tela sem pontuação proibida.
        for (m in listOf(denied.message, expired.message)) assertFalse(m.any { it in ":;—–" })
    }

    @Test fun bearerSoParaSessaoOAuthEChaveContinuaFuncionando() {
        assertTrue(trackerUsesApiKey(TrackerProvider.MDBLIST, TrackerCredentials("chave")))
        assertFalse(trackerUsesApiKey(TrackerProvider.MDBLIST, TrackerCredentials("tok", oauth = true)))
        assertFalse(trackerUsesApiKey(TrackerProvider.SIMKL, TrackerCredentials("tok")))
        // Backup antigo (token sem sessão) segue como chave da API.
        assertFalse(trackerSessionIsOAuth(null))
        assertFalse(trackerSessionIsOAuth(JSONObject().put("client_id", "c").put("refresh_token", "")))
        assertTrue(trackerSessionIsOAuth(JSONObject().put("refresh_token", "r")))
        assertTrue(trackerSessionIsOAuth(JSONObject().put("oauth", true)))
    }

    @Test fun renovaUmDiaAntesNoMdblist() {
        val now = 1_000_000_000_000L
        val mdb = TrackerCredentials("t", refresh = "r", expiresAt = now + 23 * 3_600_000L, oauth = true)
        assertTrue(trackerNeedsRefresh(TrackerProvider.MDBLIST, mdb, now))
        assertFalse(trackerNeedsRefresh(TrackerProvider.MDBLIST, mdb.copy(expiresAt = now + 2 * 86_400_000L), now))
        assertFalse(trackerNeedsRefresh(TrackerProvider.SIMKL, mdb, now))
        assertFalse(trackerNeedsRefresh(TrackerProvider.MDBLIST, TrackerCredentials("chave"), now))
        // Backup restaurado sem validade renova na hora.
        assertTrue(trackerNeedsRefresh(TrackerProvider.MDBLIST, mdb.copy(expiresAt = 0), now))
    }

    @Test fun retryAfterTemTetoDe60s() {
        assertEquals(5_000L, trackerRetryAfterMs("5"))
        assertEquals(60_000L, trackerRetryAfterMs("3600"))
        assertEquals(60_000L, trackerRetryAfterMs(null))
        assertEquals(60_000L, trackerRetryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test fun scrobbleDoMdblistDizOApp() {
        val body = trackerScrobbleBody(TrackerProvider.MDBLIST, 12, false, 0, 0, 50.0, "9.9")
        assertEquals("JOTV", body.getString("app_name"))
        assertEquals("9.9", body.getString("app_version"))
        assertFalse(trackerScrobbleBody(TrackerProvider.SIMKL, 12, false, 0, 0, 50.0).has("app_name"))
    }
}
