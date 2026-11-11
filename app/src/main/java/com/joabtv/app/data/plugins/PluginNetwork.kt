package com.joabtv.app.data.plugins

import android.util.Base64
import com.joabtv.app.data.remote.AppDns
import java.net.Inet4Address
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

@Singleton
class PluginNetwork @Inject constructor() {
    private val client = OkHttpClient.Builder().dns(object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> = AppDns.cloudflare.lookup(hostname).filter { address ->
            address is Inet4Address && PluginContract.publicUrl("http://${address.hostAddress}/").isNotBlank()
        }.ifEmpty { throw java.net.UnknownHostException("Endereço de rede não permitido") }
    }).connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun session() = Session()
    inner class Session {
        private val calls = ConcurrentHashMap.newKeySet<Call>()
        @Volatile private var closed = false
        fun cancel() { closed = true; calls.forEach(Call::cancel) }
        suspend fun fetch(data: JSONObject, maxBytes: Int = 3 * 1024 * 1024): JSONObject = withContext(Dispatchers.IO) {
            var url = data.optString("url")
            var method = data.optString("method", "GET").uppercase()
            require(method in setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"))
            var headers = PluginContract.headers(data.optJSONObject("headers"))
            var body = data.optString("body").takeIf { data.has("body") && !data.isNull("body") }
            require((body?.length ?: 0) <= 1024 * 1024)
            repeat(6) { attempt ->
                check(!closed) { "Pedido cancelado" }
                require(PluginContract.publicUrl(url).isNotBlank()) { "Endereço não permitido" }
                val builder = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0.0.0 Safari/537.36")
                headers.forEach { (key, value) -> builder.header(key, value) }
                val requestBody = if (method in setOf("GET", "HEAD")) null else (body ?: "").toRequestBody(headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.toMediaTypeOrNull())
                val call = client.newCall(builder.method(method, requestBody).build()); calls.add(call)
                call.timeout().timeout(data.optLong("timeout", 20000).coerceIn(100, 20000), TimeUnit.MILLISECONDS)
                try {
                    call.execute().use { response ->
                        val location = response.header("Location")
                        if (response.code in 300..399 && location != null && data.optString("redirect") != "manual") {
                            check(attempt < 5 && data.optString("redirect") != "error") { "Redirecionamento não permitido" }
                            val target = response.request.url.resolve(location) ?: error("Endereço inválido")
                            if (target.host != response.request.url.host || target.scheme != response.request.url.scheme || target.port != response.request.url.port) headers = headers.filterKeys { !it.equals("Authorization", true) && !it.equals("Cookie", true) }
                            if (response.code == 303 || response.code in setOf(301, 302) && method == "POST") { method = "GET"; body = null }
                            url = target.toString()
                        } else {
                            val stream = response.body?.byteStream()
                            val output = java.io.ByteArrayOutputStream()
                            if (stream != null) stream.use { input ->
                                val buffer = ByteArray(16 * 1024)
                                while (true) { val count = input.read(buffer); if (count < 0) break; check(output.size() + count <= maxBytes) { "Resposta do plugin muito grande" }; output.write(buffer, 0, count) }
                            }
                            val resultHeaders = JSONObject()
                            response.headers.names().filterNot { it.equals("set-cookie", true) }.take(80).forEach { resultHeaders.put(it.lowercase(), response.header(it).orEmpty().take(8192)) }
                            return@withContext JSONObject().put("status", response.code).put("url", response.request.url.toString()).put("headers", resultHeaders).put("bodyBase64", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                        }
                    }
                } finally { calls.remove(call) }
            }
            error("Redirecionamento não permitido")
        }
    }
}
