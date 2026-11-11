package com.joabtv.app.ui.movies

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Headers que os links de addon exigem (bloqueiam sem Referer/UA de navegador). */
internal val STREAM_HEADERS = mapOf(
    "Referer" to com.joabtv.app.data.remote.STREAM_REFERER,
    "User-Agent" to "Mozilla/5.0 (Linux; Android 14; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36",
)

/**
 * Frame do vídeo pra prévia ao arrastar a barra do player, tipo YouTube. Reusa o mesmo
 * MediaMetadataRetriever enquanto a URL não muda e guarda os últimos frames pedidos.
 *
 * Só funciona em fonte HTTP progressiva que aceita pedido de frame nessa posição. m3u8, DRM
 * ou fonte que só resolve pelo DNS customizado do app (Cloudflare) falha calado — o
 * MediaMetadataRetriever usa a pilha de rede do sistema, não o OkHttp do player — e o
 * arraste continua funcionando normal, só sem a miniatura.
 *
 * ponytail: cancelar o pedido em andamento não interrompe a chamada nativa bloqueante, só
 * descarta o resultado. Arrastar rápido pode deixar 1-2 chamadas antigas terminando sozinhas
 * na thread de IO. Sem risco de travar a UI, só desperdiça um pouco de CPU em segundo plano.
 */
internal object ScrubThumbnailer {
    private val lock = Mutex()
    private var retriever: MediaMetadataRetriever? = null
    private var retrieverUrl: String? = null
    private var frameSize: Pair<Int, Int>? = null
    private var retryAt = 0L
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = PreviewFrameCache<Bitmap>()

    /** Frame mais próximo de [posMs] em [url], ou null se a fonte não deixar. */
    suspend fun frameAt(url: String, posMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        // Prévia de torrent pediria pedaços espalhados do arquivo e roubaria banda do vídeo.
        if (com.joabtv.app.data.torrent.TorrentMagnet.isMagnet(url)) return@withContext null
        val bucket = posMs / 2000 // janela de 2s: não precisa do frame exato, e cacheia mais
        lock.withLock {
            if (retrieverUrl != url) {
                runCatching { retriever?.release() }
                retriever = null
                retrieverUrl = url
                frameSize = null
                retryAt = 0
                cache.selectSource(url)
            }
            if (bucket in cache) return@withLock cache[bucket]
            if (retriever == null) {
                if (SystemClock.elapsedRealtime() < retryAt) return@withLock null
                val r = MediaMetadataRetriever()
                val ok = runCatching { r.setDataSource(url, STREAM_HEADERS) }.isSuccess
                if (!ok) {
                    runCatching { r.release() }
                    retryAt = SystemClock.elapsedRealtime() + 5000
                    return@withLock null
                }
                retriever = r
                frameSize = runCatching {
                    previewFrameSize(
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                        r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                    )
                }.getOrNull()
            }
            val bmp = runCatching {
                val size = frameSize
                val scaled = if (Build.VERSION.SDK_INT >= 27 && size != null) runCatching {
                    retriever?.getScaledFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST, size.first, size.second)
                }.getOrNull() else null
                scaled ?: retriever?.getFrameAtTime(posMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST)?.let(::downscale)
            }.getOrNull()
            cache[bucket] = bmp
            bmp
        }
    }

    private fun downscale(full: Bitmap): Bitmap {
        val w = 160
        val h = (full.height.toLong() * w / full.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val scaled = runCatching { Bitmap.createScaledBitmap(full, w, h, true) }.getOrDefault(full)
        if (scaled !== full) full.recycle()
        return scaled
    }

    /** Libera o retriever nativo e limpa o cache. Chamar ao sair do player. */
    fun release() {
        cleanup.launch {
            lock.withLock {
                runCatching { retriever?.release() }
                retriever = null
                retrieverUrl = null
                frameSize = null
                retryAt = 0
                cache.clear()
            }
        }
    }
}
