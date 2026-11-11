package com.joabtv.app.data.plugins

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

@Singleton
class PluginEngine @Inject constructor(@ApplicationContext private val context: Context, private val network: PluginNetwork) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gate = Semaphore(32)
    private var view: WebView? = null
    private var ready = CompletableDeferred<Unit>()
    private var idle: kotlinx.coroutines.Job? = null
    private data class Task(val result: CompletableDeferred<JSONObject>, val network: PluginNetwork.Session)
    private val tasks = linkedMapOf<String, Task>()

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureView(): WebView {
        view?.let { return it }
        ready = CompletableDeferred()
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(Bridge(), "JotvPluginBridge")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView?, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val name = uri.path.orEmpty().removePrefix("/")
                    val allowed = setOf("runtime-host.html", "native-host.js", "worker.js", "runtime.js", "contract.js", "pool.js")
                    if (uri.scheme != "https" || uri.host != "plugins.jotv.invalid" || name !in allowed) {
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    }
                    return try {
                        WebResourceResponse(if (name.endsWith(".html")) "text/html" else "application/javascript", "UTF-8", 200, "OK",
                            mapOf("Content-Security-Policy" to "default-src 'none'; script-src 'self' 'unsafe-eval'; worker-src 'self'; child-src 'self'; connect-src 'none'", "Cache-Control" to "no-store"), context.assets.open("plugins/$name"))
                    } catch (_: Exception) {
                        WebResourceResponse("text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()))
                    }
                }
                override fun onPageFinished(v: WebView?, url: String?) {
                    if (url == "https://plugins.jotv.invalid/runtime-host.html") ready.complete(Unit)
                }
                override fun shouldOverrideUrlLoading(v: WebView?, request: WebResourceRequest): Boolean = request.url.host != "plugins.jotv.invalid"
            }
            view = this
            loadUrl("https://plugins.jotv.invalid/runtime-host.html")
        }
    }

    suspend fun execute(input: JSONObject): JSONObject = gate.withPermit {
        val id = UUID.randomUUID().toString()
        val task = Task(CompletableDeferred(), network.session())
        try {
            withContext(Dispatchers.Main.immediate) {
                idle?.cancel()
                val web = ensureView()
                withTimeout(20_000) { ready.await() }
                tasks[id] = task
                web.evaluateJavascript("JotvPluginHost.run(${JSONObject.quote(id)},$input)", null)
            }
            withTimeout(input.optLong("timeout", 45_000).coerceIn(1000, 120_000) + 15_000) { task.result.await() }
        } finally {
            task.network.cancel()
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) {
                tasks.remove(id)
                view?.evaluateJavascript("JotvPluginHost.cancel(${JSONObject.quote(id)})", null)
                if (tasks.isEmpty()) {
                    idle?.cancel()
                    idle = scope.launch { delay(60_000); if (tasks.isEmpty()) { view?.removeJavascriptInterface("JotvPluginBridge"); view?.destroy(); view = null } }
                }
            }
        }
    }

    fun cancel(dropWorkers: Boolean = false) { scope.launch { tasks.values.toList().forEach { it.network.cancel(); it.result.cancel() }; if (dropWorkers) view?.evaluateJavascript("JotvPluginHost.clear()", null) } }

    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(raw: String) {
            if (raw.length > 2 * 1024 * 1024) return
            val data = runCatching { JSONObject(raw) }.getOrNull() ?: return
            val id = data.optString("job")
            scope.launch {
                val task = tasks[id] ?: return@launch
                when (data.optString("type")) {
                    "fetch" -> {
                        val request = data.optJSONObject("request") ?: return@launch
                        val requestId = data.optString("id").take(20)
                        val reply = try { JSONObject().put("type", "response").put("id", requestId).put("value", task.network.fetch(request)) }
                            catch (_: Exception) { JSONObject().put("type", "response").put("id", requestId).put("error", true) }
                        if (tasks[id] !== task) return@launch
                        val text = reply.toString()
                        text.chunked(48 * 1024).forEachIndexed { index, part ->
                            if (tasks[id] !== task) return@forEachIndexed
                            val last = (index + 1) * 48 * 1024 >= text.length
                            view?.evaluateJavascript("JotvPluginHost.part(${JSONObject.quote(id)},${JSONObject.quote(requestId)},${JSONObject.quote(part)},$last)", null)
                        }
                    }
                    "result" -> task.result.complete(data.optJSONObject("value") ?: JSONObject().put("value", org.json.JSONArray()))
                    "error" -> task.result.completeExceptionally(IllegalStateException(data.optString("error", "O plugin não pôde ser executado.").take(240)))
                }
            }
        }
    }
}
