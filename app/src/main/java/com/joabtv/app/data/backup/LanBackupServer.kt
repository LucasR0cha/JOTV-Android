package com.joabtv.app.data.backup

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class LanBackupInfo(
    val ip: String,
    val port: Int,
    val pin: String,
    val url: String,
)

@Singleton
class LanBackupServer @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _info = MutableStateFlow<LanBackupInfo?>(null)
    val info: StateFlow<LanBackupInfo?> = _info.asStateFlow()

    private val _receivedJson = MutableStateFlow<String?>(null)
    val receivedJson: StateFlow<String?> = _receivedJson.asStateFlow()

    fun clearReceived() {
        _receivedJson.value = null
    }

    fun start(exportJson: String): LanBackupInfo {
        stop()
        require(exportJson.toByteArray(Charsets.UTF_8).size <= AppBackupRepository.MAX_JSON_BYTES) { "Backup grande demais." }

        val pin = "%04d".format(SecureRandom().nextInt(10_000))
        val socket = ServerSocket(0)
        val port = socket.localPort
        val ip = detectLanIp()
        val url = "http://$ip:$port/?pin=$pin"
        val info = LanBackupInfo(ip, port, pin, url)

        serverSocket = socket
        _info.value = info
        _receivedJson.value = null

        val wrongAttempts = AtomicInteger(0)

        acceptJob = scope.launch {
            while (isActive && !socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                launch {
                    try {
                        handleClient(client, exportJson, pin, wrongAttempts)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        runCatching { client.close() }
                        throw cancelled
                    } catch (_: Exception) {
                        runCatching { client.close() }
                    }
                }
            }
        }
        return info
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        _info.value = null
    }

    suspend fun pullFrom(hostInput: String, pinInput: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val host = normalizeHost(hostInput)
            val pin = cleanPin(pinInput)
            val req = Request.Builder()
                .url("http://$host/backup?pin=$pin")
                .get()
                .build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.byteStream()?.use { readBackupText(it) }.orEmpty()
                if (!res.isSuccessful) {
                    val err = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    error(err?.takeIf { it.isNotBlank() } ?: "Erro HTTP ${res.code}")
                }
                text
            }
        }
    }

    suspend fun pushTo(hostInput: String, pinInput: String, jsonBody: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val host = normalizeHost(hostInput)
                val pin = cleanPin(pinInput)
                require(jsonBody.toByteArray(Charsets.UTF_8).size <= AppBackupRepository.MAX_JSON_BYTES) { "O backup ultrapassa o limite de 8 MB" }
                val body = jsonBody.toRequestBody("text/plain; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("http://$host/backup?pin=$pin")
                    .post(body)
                    .build()
                http.newCall(req).execute().use { res ->
                    val text = res.body?.byteStream()?.use { readBackupText(it) }.orEmpty()
                    if (!res.isSuccessful) {
                        val err = runCatching { JSONObject(text).optString("error") }.getOrNull()
                        error(err?.takeIf { it.isNotBlank() } ?: "Erro HTTP ${res.code}")
                    }
                    check(runCatching { JSONObject(text).optBoolean("ok") }.getOrDefault(false)) { "O outro aparelho não confirmou o recebimento do backup" }
                }
            }
        }

    fun generateQr(content: String, sizePx: Int = 420): Bitmap? = runCatching {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            val offset = y * sizePx
            for (x in 0 until sizePx) {
                pixels[offset + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, sizePx, 0, 0, sizePx, sizePx)
        }
    }.getOrNull()

    private fun handleClient(
        socket: Socket,
        exportJson: String,
        expectedPin: String,
        wrongAttempts: AtomicInteger,
    ) {
        socket.use { sock ->
            sock.soTimeout = 15_000
            val input = BufferedInputStream(sock.getInputStream())
            val out = sock.getOutputStream()

            val headerBytes = readUntilHeaderEnd(input) ?: return
            val headerText = headerBytes.toString(Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            val requestLine = lines.firstOrNull().orEmpty().split(" ")
            if (requestLine.size < 2) return

            val method = requestLine[0].uppercase()
            val target = requestLine[1]
            val path = target.substringBefore("?")
            val queryPin = extractQueryParam(target.substringAfter("?", ""), "pin")

            var headerPin = ""
            var contentLength = 0
            for (i in 1 until lines.size) {
                val line = lines[i]
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                when (key) {
                    "x-jotv-pin" -> headerPin = value
                    "content-length" -> contentLength = value.toIntOrNull()?.coerceIn(0, AppBackupRepository.MAX_JSON_BYTES + 1024) ?: 0
                }
            }

            if (method == "OPTIONS") {
                writeHttpResponse(out, 204, "text/plain; charset=utf-8", ByteArray(0))
                return
            }

            if (path == "/" && method == "GET") {
                val html = portalHtml(queryPin.filter { it in '0'..'9' }.take(4)).toByteArray(Charsets.UTF_8)
                writeHttpResponse(out, 200, "text/html; charset=utf-8", html)
                return
            }

            val pinProvided = headerPin.ifBlank { queryPin }
            if (!constantTimeEquals(pinProvided, expectedPin)) {
                if (wrongAttempts.incrementAndGet() >= MAX_WRONG_PIN) {
                    stop()
                }
                val err = JSONObject().put("error", "PIN inválido.").toString().toByteArray(Charsets.UTF_8)
                writeHttpResponse(out, 403, "application/json; charset=utf-8", err)
                return
            }

            when {
                method == "GET" && path == "/status" -> {
                    val body = JSONObject().put("ok", true).put("platform", "android").toString().toByteArray(Charsets.UTF_8)
                    writeHttpResponse(out, 200, "application/json; charset=utf-8", body)
                }
                method == "GET" && path == "/backup" -> {
                    writeHttpResponse(out, 200, "application/json; charset=utf-8", exportJson.toByteArray(Charsets.UTF_8))
                }
                method == "POST" && path == "/backup" -> {
                    if (contentLength <= 0 || contentLength > AppBackupRepository.MAX_JSON_BYTES) {
                        val err = JSONObject().put("error", "Tamanho de backup inválido.").toString().toByteArray(Charsets.UTF_8)
                        writeHttpResponse(out, 400, "application/json; charset=utf-8", err)
                        return
                    }
                    val bodyBytes = readExact(input, contentLength)
                    val bodyText = bodyBytes.toString(Charsets.UTF_8)
                    val valid = runCatching {
                        JSONObject(bodyText).optString("format") == AppBackupRepository.FORMAT
                    }.getOrDefault(false)
                    if (!valid) {
                        val err = JSONObject().put("error", "Arquivo não é um backup válido do JoabTV.").toString().toByteArray(Charsets.UTF_8)
                        writeHttpResponse(out, 400, "application/json; charset=utf-8", err)
                        return
                    }
                    _receivedJson.value = bodyText
                    val ok = JSONObject().put("ok", true).toString().toByteArray(Charsets.UTF_8)
                    writeHttpResponse(out, 200, "application/json; charset=utf-8", ok)
                }
                else -> {
                    val err = JSONObject().put("error", "Rota não encontrada.").toString().toByteArray(Charsets.UTF_8)
                    writeHttpResponse(out, 404, "application/json; charset=utf-8", err)
                }
            }
        }
    }

    private fun readUntilHeaderEnd(input: BufferedInputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        while (buf.size() < 16_384) {
            val b = input.read()
            if (b < 0) return null
            buf.write(b)
            matched = when {
                b == '\r'.code && (matched == 0 || matched == 2) -> matched + 1
                b == '\n'.code && (matched == 1 || matched == 3) -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
            if (matched == 4) return buf.toByteArray()
        }
        return null
    }

    private fun readExact(input: BufferedInputStream, length: Int): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(out, read, length - read)
            if (n <= 0) break
            read += n
        }
        return if (read == length) out else out.copyOf(read)
    }

    private fun writeHttpResponse(
        out: java.io.OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
    ) {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Error"
        }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-JOTV-Pin\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun extractQueryParam(query: String, key: String): String {
        if (query.isBlank()) return ""
        return query.split("&").firstNotNullOfOrNull { pair ->
            val eq = pair.indexOf('=')
            val k = if (eq >= 0) pair.substring(0, eq) else pair
            if (k == key && eq >= 0) URLDecoder.decode(pair.substring(eq + 1), "UTF-8") else null
        }.orEmpty()
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun detectLanIp(): String {
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull() ?: return "127.0.0.1"
        val candidates = mutableListOf<String>()
        while (interfaces.hasMoreElements()) {
            val iface = interfaces.nextElement()
            if (!iface.isUp || iface.isLoopback) continue
            val addrs = iface.inetAddresses
            while (addrs.hasMoreElements()) {
                val addr = addrs.nextElement()
                if (addr is Inet4Address && addr.isSiteLocalAddress && !addr.isLoopbackAddress) {
                    val host = addr.hostAddress ?: continue
                    if (iface.name.startsWith("wlan", ignoreCase = true) || iface.name.startsWith("eth", ignoreCase = true)) {
                        return host
                    }
                    candidates.add(host)
                }
            }
        }
        return candidates.firstOrNull() ?: "127.0.0.1"
    }

    private fun normalizeHost(raw: String): String {
        val s = raw.trim()
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
            .replace(" ", "")
        val idx = s.indexOf(':')
        require(idx > 0) { "Digite o endereço com IP e porta (exemplo 192.168.1.50:48123)." }
        val host = s.substring(0, idx)
        val port = s.substring(idx + 1).toIntOrNull()
        require(host.isNotBlank() && port != null && port in 1..65535) { "Endereço ou porta inválida." }
        val parts = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        require(parts.size == 4 && host.split('.').size == 4 &&
            (parts[0] == 10 || parts[0] == 127 || parts[0] == 192 && parts[1] == 168 ||
                parts[0] == 172 && parts[1] in 16..31 || parts[0] == 169 && parts[1] == 254)) { "Use o IP de um aparelho da rede local" }
        return "$host:$port"
    }

    private fun cleanPin(raw: String): String {
        val p = raw.filter { it.isDigit() }
        require(p.length == 4) { "Digite o PIN de 4 números." }
        return p
    }

    private fun portalHtml(pin: String): String = """
        <!doctype html>
        <html lang="pt-BR">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width,initial-scale=1">
          <title>JoabTV Backup</title>
          <style>
            body{margin:0;background:#0d1117;color:#f0f4f8;font-family:system-ui,sans-serif;padding:24px}
            .card{max-width:460px;margin:0 auto;background:#161b22;border:1px solid #30363d;border-radius:16px;padding:24px}
            h1{margin:0 0 8px;font-size:22px}
            p{margin:0 0 18px;color:#9fb0c3;font-size:14px;line-height:1.45}
            .btn{display:block;width:100%;padding:14px 16px;margin-top:12px;border-radius:12px;border:0;font-size:15px;font-weight:600;cursor:pointer;text-align:center;text-decoration:none;box-sizing:border-box}
            .primary{background:#2e8bff;color:#fff}
            .secondary{background:#21262d;color:#f0f4f8;border:1px solid #30363d}
            input[type=file]{margin-top:12px;width:100%;color:#9fb0c3}
            .msg{margin-top:14px;font-size:13px;color:#58d68d}
          </style>
        </head>
        <body>
          <div class="card">
            <h1>Backup JoabTV</h1>
            <p>Transferência direta pelo Wi-Fi local, sem servidor na internet.</p>
            <label for="pin">PIN mostrado no aplicativo</label>
            <input id="pin" value="$pin" inputmode="numeric" maxlength="4" autocomplete="off">
            <button class="btn primary" onclick="downloadBackup()">Baixar backup deste aparelho</button>
            <input id="file" type="file" accept=".json,application/json">
            <button class="btn secondary" onclick="send()">Enviar arquivo JSON para este aparelho</button>
            <div id="msg" class="msg"></div>
          </div>
          <script>
            async function send() {
              var f = document.getElementById('file').files[0];
              var m = document.getElementById('msg');
              if (!f) { m.textContent = 'Escolha um arquivo .json primeiro.'; return; }
              var txt = await f.text();
              var r = await fetch('/backup?pin=' + encodeURIComponent(document.getElementById('pin').value), { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: txt });
              m.textContent = r.ok ? 'Backup enviado. Confirme a restauração na tela do app.' : 'Erro ao enviar arquivo.';
            }
            function downloadBackup() {
              location.href = '/backup?pin=' + encodeURIComponent(document.getElementById('pin').value);
            }
          </script>
        </body>
        </html>
    """.trimIndent()

    companion object {
        private const val MAX_WRONG_PIN = 12
    }
}
