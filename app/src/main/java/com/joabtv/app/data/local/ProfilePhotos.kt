package com.joabtv.app.data.local

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import coil.size.Size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Foto do perfil. O campo avatar do [Profile] guarda um destes valores.
 * Vazio é sem foto (aparece a inicial na cor do perfil). Começando com https:// é uma foto de fora (do Nuvio ou
 * do Stremio, ou escolhida do catálogo do Nuvio). Começando com file: é uma foto escolhida na galeria, que fica
 * reduzida em arquivo próprio dentro do app, no formato "file:<perfil>.jpg?v=<hora>" (a parte depois do ponto de
 * interrogação só muda para o Coil largar a imagem velha).
 */
object ProfilePhotos {
    const val FILE = "file:"
    private const val DIR = "avatars"
    private const val SIDE = 320

    /** Nome do arquivo de um perfil que ainda não foi criado. Vira o do perfil em [adopt]. */
    const val NEW_PROFILE = "novo"

    fun isFile(avatar: String) = avatar.startsWith(FILE)

    /** Só foto por https. O endereço vem de serviço de fora e não deve abrir outro tipo de coisa. */
    fun isRemote(avatar: String) = avatar.startsWith("https://", ignoreCase = true) && avatar.none(Char::isWhitespace) && avatar.length <= 2048

    /** O que entregar ao Coil. null sem foto. */
    fun model(context: Context, avatar: String): Any? = when {
        avatar.isBlank() -> null
        isFile(avatar) -> fileOf(context, avatar.removePrefix(FILE).substringBefore('?')).takeIf { it.exists() }
        isRemote(avatar) -> avatar
        else -> null
    }

    private fun fileOf(context: Context, name: String): File {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        // Só letras, números e pontuação simples, então o nome nunca sai da pasta. Nome só de pontos ("..") não vale.
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(dir, if (safe.trim('.').isEmpty()) "invalido" else safe)
    }

    /**
     * Lê a imagem escolhida, reduz e corta quadrada ao centro (320 px) e grava em JPEG no arquivo do perfil.
     * O Coil cuida da rotação do arquivo e da amostragem, então foto de câmera de 12 megapixels não estoura memória.
     * Devolve o valor para o campo avatar, ou null se não deu para ler a imagem.
     */
    suspend fun savePicked(context: Context, uri: Uri, profileId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = ImageRequest.Builder(context).data(uri)
                .size(Size(SIDE, SIDE)).scale(Scale.FILL).precision(Precision.INEXACT)
                .allowHardware(false).allowRgb565(false).build()
            val result = context.imageLoader.execute(request) as? SuccessResult ?: return@runCatching null
            val bmp = (result.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
            val side = minOf(bmp.width, bmp.height)
            val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
            val out = if (side > SIDE) Bitmap.createScaledBitmap(square, SIDE, SIDE, true) else square
            val name = "$profileId.jpg"
            fileOf(context, name).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            "$FILE$name?v=${System.currentTimeMillis()}"
        }.getOrNull()
    }

    /** O perfil novo ganhou id. A foto escolhida antes de ele existir passa para o arquivo dele. */
    fun adopt(context: Context, profileId: String, avatar: String): String {
        if (!isFile(avatar)) return avatar
        val from = fileOf(context, avatar.removePrefix(FILE).substringBefore('?'))
        if (!from.exists()) return ""
        val name = "$profileId.jpg"
        val to = fileOf(context, name)
        if (from != to) { to.delete(); from.renameTo(to) }
        return "$FILE$name?v=${System.currentTimeMillis()}"
    }

    /** Apaga o arquivo da foto de um perfil removido. */
    fun delete(context: Context, profileId: String) {
        runCatching { fileOf(context, "$profileId.jpg").delete() }
    }
}
