package com.joabtv.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePhotosTest {
    @Test fun corDoNuvioViraACorMaisPertoDoJotv() {
        // Azul do Nuvio cai no azul do JOTV, vermelho no vermelho, verde no verde.
        assertEquals(0, nearestProfileColor("#1E88E5"))
        assertEquals(1, nearestProfileColor("#E53935"))
        assertEquals(2, nearestProfileColor("#43A047"))
        assertEquals(3, nearestProfileColor("#FB8C00"))
        assertEquals(4, nearestProfileColor("#8E24AA"))
        assertEquals(5, nearestProfileColor("#FF69B4"))
        // O rosa escuro do Nuvio (D81B60) está mais perto do vermelho do JOTV que do rosa.
        assertEquals(1, nearestProfileColor("#D81B60"))
    }

    @Test fun textoQueNaoECorNaoMudaACor() {
        assertNull(nearestProfileColor(""))
        assertNull(nearestProfileColor("azul"))
        assertNull(nearestProfileColor("#12345"))
    }

    @Test fun fotoDeForaSoValePorHttpsSemEspaco() {
        assertTrue(ProfilePhotos.isRemote("https://api.nuvio.tv/storage/v1/object/public/avatars/a.png"))
        assertFalse(ProfilePhotos.isRemote("http://exemplo.invalid/a.png"))
        assertFalse(ProfilePhotos.isRemote("file:///sdcard/a.png"))
        assertFalse(ProfilePhotos.isRemote("https://exemplo.invalid/a b.png"))
        assertFalse(ProfilePhotos.isRemote("https://exemplo.invalid/" + "a".repeat(2100)))
        assertFalse(ProfilePhotos.isRemote(""))
    }

    @Test fun fotoDeArquivoTemOPrefixoFile() {
        assertTrue(ProfilePhotos.isFile("file:main.jpg?v=1"))
        assertFalse(ProfilePhotos.isFile("https://x"))
        assertFalse(ProfilePhotos.isFile(""))
    }
}
