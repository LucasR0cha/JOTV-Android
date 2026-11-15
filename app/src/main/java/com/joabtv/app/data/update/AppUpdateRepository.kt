package com.joabtv.app.data.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.joabtv.app.BuildConfig
import com.joabtv.app.data.remote.AppDns
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

data class AppUpdateState(
    val checking: Boolean = false,
    val release: AppRelease? = null,
    val show: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val ready: Boolean = false,
    val message: String = "",
)

@Singleton
class AppUpdateRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("jotv_updates", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val client = OkHttpClient.Builder().dns(AppDns.cloudflare).connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS).followRedirects(false).build()
    private val _state = MutableStateFlow(AppUpdateState())
    val state = _state.asStateFlow()
    private val prefix = BuildConfig.UPDATE_REPOSITORY + "."
    private var promptGeneration = 0
    private val target get() = File(context.cacheDir, "updates/JOTV-update.apk")

    init {
        if (!BuildConfig.DEBUG) {
            val cached = cachedAppRelease(prefs.getString(prefix + "release", "").orEmpty(), BuildConfig.VERSION_NAME, BuildConfig.UPDATE_REPOSITORY)
            _state.value = AppUpdateState(release = cached, show = cached != null && prefs.getString(prefix + "skipped", "") != cached.version)
        }
    }

    private suspend fun releases(): AppRelease? = withContext(Dispatchers.IO) {
        val all = JSONArray()
        for (page in 1..3) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url("https://api.github.com/repos/${BuildConfig.UPDATE_REPOSITORY}/releases?per_page=100&page=$page")
                .header("Accept", "application/vnd.github+json").header("User-Agent", "JOTV/${BuildConfig.VERSION_NAME}").build()
            val data = client.newCall(request).execute().use { response ->
                check(response.isSuccessful) { if (response.code in listOf(403, 429)) "O GitHub limitou as consultas. Tente mais tarde" else "Não foi possível consultar as atualizações" }
                val bytes = response.body?.byteStream()?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; check(output.size() + n <= 3 * 1024 * 1024); output.write(buffer, 0, n) }
                    output.toByteArray()
                } ?: error("O GitHub devolveu uma resposta vazia")
                JSONArray(String(bytes, Charsets.UTF_8))
            }
            for (i in 0 until data.length()) all.put(data.get(i))
            if (data.length() < 100) break
        }
        selectAndroidRelease(all, BuildConfig.VERSION_NAME, BuildConfig.UPDATE_REPOSITORY)
    }

    suspend fun check(manual: Boolean) {
        if (!lock.tryLock()) return
        val generation = promptGeneration
        try {
            if (!manual && (BuildConfig.DEBUG || System.currentTimeMillis() - prefs.getLong(prefix + "checked", 0) < 86_400_000)) return
            _state.update { it.copy(checking = true, message = "", show = manual || it.show) }
            val release = releases()
            prefs.edit().putLong(prefix + "checked", System.currentTimeMillis()).putString(prefix + "release", release?.toJson()?.toString().orEmpty()).apply()
            _state.update { it.copy(checking = false, release = release, ready = false,
                show = generation == promptGeneration && (manual || release != null && prefs.getString(prefix + "skipped", "") != release.version),
                message = if (release == null) "O JOTV já está na versão mais recente para este aparelho" else "") }
        } catch (cancelled: CancellationException) { _state.update { it.copy(checking = false) }; throw cancelled }
        catch (error: Exception) { _state.update { it.copy(checking = false, show = manual && generation == promptGeneration, message = error.message?.takeIf { !it.contains("http", true) } ?: "Não foi possível consultar as atualizações. Tente novamente") } }
        finally { lock.unlock() }
    }

    fun dismiss() {
        if (_state.value.downloading) return
        promptGeneration++
        _state.value.release?.let { prefs.edit().putString(prefix + "skipped", it.version).apply() }
        _state.update { it.copy(show = false) }
    }

    fun cancelDownload() {
        promptGeneration++
        client.dispatcher.cancelAll()
        _state.update { it.copy(show = false, downloading = false, message = "") }
    }

    suspend fun download() = withContext(Dispatchers.IO) {
        lock.withLock {
            val expected = _state.value.release ?: return@withLock
            if (_state.value.downloading || expected.sha256.isBlank()) return@withLock
            _state.update { it.copy(downloading = true, progress = 0f, message = "Baixando e verificando a atualização…", ready = false) }
            val part = File(context.cacheDir, "updates/JOTV-update-download.apk")
            try {
                val latest = releases()
                check(latest != null && latest.assetId == expected.assetId && latest.url == expected.url && latest.size == expected.size && latest.sha256 == expected.sha256) { "A versão publicada mudou. Verifique as atualizações novamente" }
                part.parentFile!!.mkdirs()
                var url = expected.url
                var response: okhttp3.Response? = null
                for (redirect in 0..5) {
                    currentCoroutineContext().ensureActive()
                    val parsed = url.toHttpUrl()
                    check(parsed.scheme == "https" && parsed.port == 443 && parsed.username.isEmpty() && parsed.password.isEmpty() && parsed.host in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) { "Destino de atualização inválido" }
                    val next = client.newCall(Request.Builder().url(parsed).header("User-Agent", "JOTV/${BuildConfig.VERSION_NAME}").build()).execute()
                    if (next.isRedirect) {
                        val location = next.header("Location"); next.close()
                        check(redirect < 5 && location != null) { "Não foi possível baixar a atualização" }
                        url = parsed.resolve(location)!!.toString()
                    } else { response = next; break }
                }
                checkNotNull(response).use { result ->
                    check(result.isSuccessful) { "O download não foi concluído" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    result.body!!.byteStream().use { input -> part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer); if (n < 0) break
                            total += n; check(total <= expected.size) { "Tamanho de atualização inválido" }
                            output.write(buffer, 0, n); digest.update(buffer, 0, n)
                            _state.update { it.copy(progress = total.toFloat() / expected.size) }
                        }
                    } }
                    check(total == expected.size && digest.digest().joinToString("") { "%02x".format(it) } == expected.sha256) { "A verificação do arquivo falhou. Baixe novamente" }
                }
                verifyPackage(part, expected)
                if (target.exists()) check(target.delete())
                check(part.renameTo(target))
                _state.update { it.copy(downloading = false, ready = true, progress = 1f, message = "Download verificado. Confirme a instalação no Android") }
            } catch (cancelled: CancellationException) { part.delete(); _state.update { it.copy(downloading = false) }; throw cancelled }
            catch (error: Exception) { part.delete(); _state.update { it.copy(downloading = false, ready = false, message = error.message?.takeIf { !it.contains("http", true) } ?: "Não foi possível baixar a atualização. Tente novamente") } }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, release: AppRelease) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val incoming = manager.getPackageArchiveInfo(file.path, flags) ?: error("APK de atualização inválido")
        val installed = manager.getPackageInfo(context.packageName, flags)
        check(incoming.packageName == context.packageName && incoming.versionName == release.version) { "O pacote não corresponde a esta atualização" }
        val next = if (Build.VERSION.SDK_INT >= 28) incoming.longVersionCode else incoming.versionCode.toLong()
        val current = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        check(next > current) { "A atualização não é mais recente que o aplicativo instalado" }
        fun signatures(info: android.content.pm.PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        val before = signatures(installed).orEmpty().map { it.toCharsString() }.toSet()
        val after = signatures(incoming).orEmpty().map { it.toCharsString() }.toSet()
        check(before.isNotEmpty() && before == after) { "A assinatura da atualização é diferente do JOTV instalado" }
    }

    fun packageFile(): File? = target.takeIf { state.value.ready && it.isFile }
}
