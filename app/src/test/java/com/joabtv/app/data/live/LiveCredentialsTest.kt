package com.joabtv.app.data.live

import org.junit.Assert.assertEquals
import org.junit.Test

/** Backup sem segredos. O endereço da lista e do guia sai sem usuário, senha e chave. */
class LiveCredentialsTest {
    @Test
    fun tiraUsuarioESenhaDaConsulta() {
        assertEquals(
            "http://iptv.example.test:8080/get.php?type=m3u_plus&output=ts",
            LiveCredentials.strip("http://iptv.example.test:8080/get.php?username=fulano&password=segredo&type=m3u_plus&output=ts"),
        )
    }

    @Test
    fun tiraSoOQueECredencialEMantemOResto() {
        assertEquals(
            "https://guia.example.test/epg.xml?dias=3#topo",
            LiveCredentials.strip("https://guia.example.test/epg.xml?token=abc&dias=3&API_KEY=xyz#topo"),
        )
    }

    @Test
    fun consultaSoDeCredencialSomeComOPontoDeInterrogacao() {
        assertEquals("http://iptv.example.test/lista.m3u", LiveCredentials.strip("http://iptv.example.test/lista.m3u?user=a&pass=b"))
    }

    @Test
    fun tiraUsuarioESenhaAntesDoArroba() {
        assertEquals("https://iptv.example.test/lista.m3u", LiveCredentials.strip("https://fulano:segredo@iptv.example.test/lista.m3u"))
    }

    @Test
    fun enderecoSemCredencialVoltaIgual() {
        assertEquals("https://iptv.example.test/lista.m3u?grupo=filmes", LiveCredentials.strip("https://iptv.example.test/lista.m3u?grupo=filmes"))
        assertEquals("", LiveCredentials.strip(""))
    }

    @Test
    fun tiraTambemOsNomesCurtosDeCredencial() {
        assertEquals(
            "http://iptv.example.test/get.php?type=m3u",
            LiveCredentials.strip("http://iptv.example.test/get.php?u=a&p=b&uid=7&sig=xyz&type=m3u"),
        )
    }

    @Test
    fun nomeComPorcentagemEntendidoComoNomeDoParametro() {
        assertEquals("http://iptv.example.test/get.php?type=m3u", LiveCredentials.strip("http://iptv.example.test/get.php?%75sername=a&type=m3u"))
    }
}
