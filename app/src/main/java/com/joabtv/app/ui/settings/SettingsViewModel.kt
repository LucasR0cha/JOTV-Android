package com.joabtv.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.local.PlatformMode
import com.joabtv.app.data.local.PlatformPrefs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val platformPrefs: PlatformPrefs,
    private val healthChecker: com.joabtv.app.data.remote.SourceHealthChecker,
    private val trakt: com.joabtv.app.data.remote.TraktService,
    private val library: com.joabtv.app.data.repository.LibraryRepository,
    private val hub: com.joabtv.app.data.accounts.AccountsHub,
    private val backupRepo: com.joabtv.app.data.backup.AppBackupRepository,
    private val lanBackup: com.joabtv.app.data.backup.LanBackupServer,
    private val trackers: com.joabtv.app.data.remote.MultiTrackerService,
    private val trackerSync: com.joabtv.app.data.remote.TrackerSyncService,
) : ViewModel() {

    // ── Seu catálogo (os cinco cartões do topo) ──
    private fun buildCatalog(): CatalogState {
        val addons = platformPrefs.addons.value
        val cols = platformPrefs.collectionsFlow.value
        val h = homeSnap(
            platformPrefs.homeLayout, platformPrefs.catalogOnFlow.value, platformPrefs.heroSourceFlow.value, addons, cols,
            platformPrefs.homeSeen, platformPrefs.homeKeep,
        )
        return CatalogState(
            hub.nuvioState.value, hub.stremioState.value, platformPrefs.importLog, hub.running.value, h,
            AddonsSnap(addons.size, addons.count { it.enabled }, h.addonRows), ColsSnap(cols.size, cols.sumOf { it.folders.size }),
            platformPrefs.profile.name, platformPrefs.isKids,
        )
    }

    /** Quem não é fluxo (o que o perfil já viu em Minha Início) só chega aqui quando a tela volta, por [refreshCatalog]. */
    private val tick = MutableStateFlow(0)
    fun refreshCatalog() { tick.value++ }

    /** Estado dos cartões. Refaz quando muda conta, registro de importação, fileiras, addons, coleções ou perfil. */
    val catalog: StateFlow<CatalogState> = combine(
        listOf<kotlinx.coroutines.flow.Flow<Any?>>(
            hub.nuvioState, hub.stremioState, platformPrefs.importLogFlow, hub.running, platformPrefs.homeLayoutFlow,
            platformPrefs.catalogOnFlow, platformPrefs.heroSourceFlow, platformPrefs.addons, platformPrefs.collectionsFlow, platformPrefs.profileId, tick,
        ),
    ) { buildCatalog() }.stateIn(viewModelScope, SharingStarted.Eagerly, buildCatalog())

    /** Destaque da Início em uso (vazio é automático) e as opções para escolher. */
    val heroSource: StateFlow<String> = platformPrefs.heroSourceFlow
    fun heroChoices(): List<Pair<String, String>> = heroChoicesOf(platformPrefs.homeLayout)
    fun setHeroSource(key: String) { platformPrefs.heroSource = key }

    // ── Trakt (só aparece com as chaves no local.properties) ──
    val traktConfigured = trakt.configured
    private val _traktUser = MutableStateFlow(trakt.user())
    /** Conta conectada no perfil ativo. null sem login. */
    val traktUser: StateFlow<String?> = _traktUser.asStateFlow()
    /** Código do login em andamento. null fora do login. */
    private val _traktCode = MutableStateFlow<com.joabtv.app.data.remote.TraktService.DeviceCode?>(null)
    val traktCode: StateFlow<com.joabtv.app.data.remote.TraktService.DeviceCode?> = _traktCode.asStateFlow()
    /** Chamada de rede do Trakt em andamento (conectar ou sincronizar). Trava o segundo clique. */
    private val _traktBusy = MutableStateFlow<String?>(null)
    val traktBusy: StateFlow<String?> = _traktBusy.asStateFlow()
    private var loginJob: kotlinx.coroutines.Job? = null

    fun traktConnect() {
        if (_traktBusy.value != null) return
        _traktBusy.value = "Pedindo código…"
        loginJob = viewModelScope.launch {
            val code = trakt.startLogin()
            if (code == null) { _traktBusy.value = null; return@launch }
            _traktCode.value = code
            _traktBusy.value = "Aguardando confirmação…"
            val name = trakt.waitLogin(code)
            _traktCode.value = null
            _traktBusy.value = null
            if (name != null) _traktUser.value = name
        }
    }

    fun traktCancel() { loginJob?.cancel(); _traktCode.value = null; _traktBusy.value = null }

    fun traktSync() {
        if (_traktBusy.value != null) return
        _traktBusy.value = "Salvando…"
        viewModelScope.launch { trakt.sync(); _traktBusy.value = null }
    }

    fun traktLogout() { trakt.logout(); _traktUser.value = null }

    // ── Status das fontes (health-check) ──
    private val _sourceStatus = MutableStateFlow<List<com.joabtv.app.data.remote.SourceStatus>>(emptyList())
    val sourceStatus: StateFlow<List<com.joabtv.app.data.remote.SourceStatus>> = _sourceStatus.asStateFlow()

    private val _checkingHealth = MutableStateFlow(false)
    val checkingHealth: StateFlow<Boolean> = _checkingHealth.asStateFlow()

    fun checkSourcesHealth() {
        if (_checkingHealth.value) return
        viewModelScope.launch {
            _checkingHealth.value = true
            _sourceStatus.value = runCatching { healthChecker.checkAll() }.getOrDefault(emptyList())
            _checkingHealth.value = false
        }
    }

    // ── Perfil e reprodução (valem para o perfil ativo) ──
    val profiles = platformPrefs.profiles
    val profileId = platformPrefs.profileId
    val appearance = platformPrefs.appearanceFlow
    fun setTheme(theme: com.joabtv.app.data.local.AppTheme) {
        platformPrefs.appearance = platformPrefs.appearance.copy(theme = theme)
    }
    fun setMotion(motion: com.joabtv.app.data.local.InterfaceMotion) {
        platformPrefs.appearance = platformPrefs.appearance.copy(motion = motion)
    }
    fun setMenu(menu: com.joabtv.app.data.local.MenuStyle) {
        platformPrefs.appearance = platformPrefs.appearance.copy(menu = menu)
    }
    fun setUiSize(size: com.joabtv.app.data.local.InterfaceSize) {
        platformPrefs.appearance = platformPrefs.appearance.copy(uiSize = size)
    }

    val frameMeter: StateFlow<Boolean> = platformPrefs.frameMeterFlow
    fun setFrameMeter(on: Boolean) { platformPrefs.frameMeter = on }

    val hasParentalPin: Boolean get() = platformPrefs.hasParentalPin
    fun setParentalPin(pin: String) = platformPrefs.setParentalPin(pin)

    private val _audio = MutableStateFlow(platformPrefs.audioPref)
    val audioPref: StateFlow<String> = _audio.asStateFlow()
    fun setAudio(v: String) { platformPrefs.audioPref = v; _audio.value = v }

    private val _maxQuality = MutableStateFlow(platformPrefs.maxQuality)
    val maxQuality: StateFlow<Int> = _maxQuality.asStateFlow()
    fun setMaxQuality(v: Int) { platformPrefs.maxQuality = v; _maxQuality.value = v }

    // Mesmo valor que a Início e o Personalizar Início seguem, assim os dois botões nunca discordam.
    val heroTrailer: StateFlow<Boolean> = platformPrefs.heroTrailerFlow
    fun setHeroTrailer(v: Boolean) { platformPrefs.heroTrailer = v }

    // Legenda do perfil. Ligada ao abrir e o idioma em que ela liga sozinha.
    private val _subOn = MutableStateFlow(platformPrefs.subtitleOn)
    val subOn: StateFlow<Boolean> = _subOn.asStateFlow()
    fun setSubOn(v: Boolean) { platformPrefs.subtitleOn = v; _subOn.value = v }

    private val _subLang = MutableStateFlow(platformPrefs.subLang)
    val subLang: StateFlow<String> = _subLang.asStateFlow()
    fun setSubLang(v: String) { platformPrefs.subLang = v; _subLang.value = v }

    // Tamanho e fundo da legenda. O player lê os mesmos valores do perfil.
    private val _subSize = MutableStateFlow(platformPrefs.subSize)
    val subSize: StateFlow<Float> = _subSize.asStateFlow()
    fun setSubSize(v: Float) { platformPrefs.subSize = v; _subSize.value = v }

    private val _subBg = MutableStateFlow(platformPrefs.subBg)
    val subBg: StateFlow<Boolean> = _subBg.asStateFlow()
    fun setSubBg(v: Boolean) { platformPrefs.subBg = v; _subBg.value = v }

    // Preferências avançadas de reprodução e troca de fontes
    private val _autoSwitch = MutableStateFlow(platformPrefs.autoSwitchSources)
    val autoSwitch: StateFlow<Boolean> = _autoSwitch.asStateFlow()
    fun setAutoSwitch(v: Boolean) { platformPrefs.autoSwitchSources = v; _autoSwitch.value = v }

    private val _sourceTimeout = MutableStateFlow(platformPrefs.sourceTimeoutSec)
    val sourceTimeout: StateFlow<Int> = _sourceTimeout.asStateFlow()
    fun setSourceTimeout(v: Int) { platformPrefs.sourceTimeoutSec = v; _sourceTimeout.value = v }

    private val _autoUpgrade = MutableStateFlow(platformPrefs.autoUpgradeQuality)
    val autoUpgrade: StateFlow<Boolean> = _autoUpgrade.asStateFlow()
    fun setAutoUpgrade(v: Boolean) { platformPrefs.autoUpgradeQuality = v; _autoUpgrade.value = v }

    private val _debridCachedOnly = MutableStateFlow(platformPrefs.debridCachedOnly)
    val debridCachedOnly: StateFlow<Boolean> = _debridCachedOnly.asStateFlow()
    fun setDebridCachedOnly(v: Boolean) { platformPrefs.debridCachedOnly = v; _debridCachedOnly.value = v }

    // Trackers e sincronização de reprodução
    val trackerStatus = trackers.status
    fun trackerClientId(provider: com.joabtv.app.data.remote.TrackerProvider) = trackers.clientId(provider)
    fun trackerToken(provider: com.joabtv.app.data.remote.TrackerProvider) = trackers.savedToken(provider)
    suspend fun connectTracker(provider: com.joabtv.app.data.remote.TrackerProvider, token: String, clientId: String): Boolean {
        if (!trackers.connect(provider, token, clientId)) return false
        trackerSync.syncInBackground(provider)
        return true
    }
    fun disconnectTracker(provider: com.joabtv.app.data.remote.TrackerProvider) = trackers.disconnect(provider)
    suspend fun startTrackerLogin(provider: com.joabtv.app.data.remote.TrackerProvider, clientId: String) = trackers.startLogin(provider, clientId)
    suspend fun waitTrackerLogin(provider: com.joabtv.app.data.remote.TrackerProvider, code: com.joabtv.app.data.remote.MultiTrackerService.DeviceCode): Boolean {
        if (!trackers.waitLogin(provider, code)) return false
        trackerSync.syncInBackground(provider)
        return true
    }
    private val _simklToken = MutableStateFlow(platformPrefs.simklToken)
    val simklToken: StateFlow<String> = _simklToken.asStateFlow()
    fun setSimklToken(v: String) { platformPrefs.simklToken = v; _simklToken.value = v }

    private val _wetrakrKey = MutableStateFlow(platformPrefs.wetrakrKey)
    val wetrakrKey: StateFlow<String> = _wetrakrKey.asStateFlow()
    fun setWetrakrKey(v: String) { platformPrefs.wetrakrKey = v; _wetrakrKey.value = v }

    private val _publicmetadbKey = MutableStateFlow(platformPrefs.publicmetadbKey)
    val publicmetadbKey: StateFlow<String> = _publicmetadbKey.asStateFlow()
    fun setPublicmetadbKey(v: String) { platformPrefs.publicmetadbKey = v; _publicmetadbKey.value = v }

    private val _mdblistKey = MutableStateFlow(platformPrefs.mdblistKey)
    val mdblistKey: StateFlow<String> = _mdblistKey.asStateFlow()
    fun setMdblistKey(v: String) { platformPrefs.mdblistKey = v; _mdblistKey.value = v }

    /** Apaga as posições salvas do perfil ativo. Minha Lista não muda. */
    fun clearContinueWatching() { viewModelScope.launch { library.clearHistory() } }

    private val _platformMode = MutableStateFlow(platformPrefs.platformMode)
    val platformMode: StateFlow<PlatformMode> = _platformMode.asStateFlow()

    private val _customTmdbApiKey = MutableStateFlow(platformPrefs.customTmdbApiKey)
    val customTmdbApiKey: StateFlow<String> = _customTmdbApiKey.asStateFlow()

    /** Addons instalados, reativo: instalar pela Loja já atualiza a contagem aqui sozinho. */
    val addons: StateFlow<List<com.joabtv.app.data.local.AddonEntry>> = platformPrefs.addons

    fun setPlatformMode(mode: PlatformMode) {
        platformPrefs.platformMode = mode
        _platformMode.value = mode
    }

    fun setTmdbApiKey(key: String) {
        platformPrefs.customTmdbApiKey = key
        _customTmdbApiKey.value = platformPrefs.customTmdbApiKey
    }

    // ── Backup e transferência sem servidor ──
    val lanInfo: StateFlow<com.joabtv.app.data.backup.LanBackupInfo?> = lanBackup.info
    val lanReceivedJson: StateFlow<String?> = lanBackup.receivedJson

    private val _backupBusy = MutableStateFlow<String?>(null)
    val backupBusy: StateFlow<String?> = _backupBusy.asStateFlow()

    private val _backupPreview = MutableStateFlow<com.joabtv.app.data.backup.BackupPreview?>(null)
    val backupPreview: StateFlow<com.joabtv.app.data.backup.BackupPreview?> = _backupPreview.asStateFlow()

    private val _backupMessage = MutableStateFlow<String?>(null)
    val backupMessage: StateFlow<String?> = _backupMessage.asStateFlow()

    fun clearBackupMessage() { _backupMessage.value = null }
    fun clearBackupPreview() { _backupPreview.value = null }
    fun clearLanReceived() { lanBackup.clearReceived() }
    fun suggestedBackupFilename(): String = backupRepo.suggestedFilename()
    fun generateQrBitmap(url: String) = lanBackup.generateQr(url)

    suspend fun exportBackupJson(includeSecrets: Boolean): String = backupRepo.exportJson(includeSecrets)

    fun exportToDownloads(includeSecrets: Boolean) {
        if (_backupBusy.value != null) return
        viewModelScope.launch {
            _backupBusy.value = "Exportando…"
            try {
                val json = backupRepo.exportJson(includeSecrets)
                val res = backupRepo.saveToDownloads(backupRepo.suggestedFilename(), json)
                _backupMessage.value = res.fold(
                    onSuccess = { "Backup salvo em $it" },
                    onFailure = { it.message ?: "Não foi possível salvar o backup." },
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { _backupMessage.value = "Não foi possível preparar o backup. Tente novamente" }
            finally { _backupBusy.value = null }
        }
    }

    fun inspectBackupText(text: String) {
        backupRepo.inspect(text).fold(
            onSuccess = { _backupPreview.value = it },
            onFailure = { _backupMessage.value = it.message ?: "Arquivo de backup inválido." },
        )
    }

    fun confirmRestore(restoreSecrets: Boolean = true) {
        val preview = _backupPreview.value ?: return
        if (_backupBusy.value != null) return
        _backupPreview.value = null
        viewModelScope.launch {
            _backupBusy.value = "Restaurando…"
            val res = backupRepo.importJson(preview.rawText, restoreSecrets)
            _backupBusy.value = null
            res.fold(
                onSuccess = { s ->
                    reloadStateAfterRestore()
                    val base = "Backup restaurado (${s.addons} addons, ${s.history} progresso, ${s.watchlist} na lista)"
                    _backupMessage.value = if (s.pluginErrors > 0) "$base com ${s.pluginErrors} falha de plugin" else base
                },
                onFailure = { e ->
                    _backupMessage.value = e.message ?: "Falha ao restaurar o backup."
                },
            )
        }
    }

    fun startLanBackupServer(includeSecrets: Boolean) {
        if (_backupBusy.value != null) return
        viewModelScope.launch {
            _backupBusy.value = "Abrindo Wi-Fi…"
            runCatching {
                val json = backupRepo.exportJson(includeSecrets)
                lanBackup.start(json)
            }.onFailure { e ->
                _backupMessage.value = e.message ?: "Não foi possível abrir o servidor Wi-Fi."
            }
            _backupBusy.value = null
        }
    }

    fun stopLanBackupServer() { lanBackup.stop() }

    fun pullLanBackup(host: String, pin: String) {
        if (_backupBusy.value != null) return
        viewModelScope.launch {
            _backupBusy.value = "Buscando no Wi-Fi…"
            lanBackup.pullFrom(host, pin).fold(
                onSuccess = { json -> inspectBackupText(json) },
                onFailure = { e -> _backupMessage.value = e.message ?: "Não foi possível buscar pelo Wi-Fi." },
            )
            _backupBusy.value = null
        }
    }

    fun pushLanBackup(host: String, pin: String, includeSecrets: Boolean) {
        if (_backupBusy.value != null) return
        viewModelScope.launch {
            _backupBusy.value = "Enviando no Wi-Fi…"
            val json = backupRepo.exportJson(includeSecrets)
            lanBackup.pushTo(host, pin, json).fold(
                onSuccess = { _backupMessage.value = "Backup enviado para $host" },
                onFailure = { e -> _backupMessage.value = e.message ?: "Falha ao enviar pelo Wi-Fi." },
            )
            _backupBusy.value = null
        }
    }

    fun notifyBackupMessage(msg: String) { _backupMessage.value = msg }

    private fun reloadStateAfterRestore() {
        _audio.value = platformPrefs.audioPref
        _maxQuality.value = platformPrefs.maxQuality
        _subOn.value = platformPrefs.subtitleOn
        _subLang.value = platformPrefs.subLang
        _subSize.value = platformPrefs.subSize
        _subBg.value = platformPrefs.subBg
        _autoSwitch.value = platformPrefs.autoSwitchSources
        _sourceTimeout.value = platformPrefs.sourceTimeoutSec
        _autoUpgrade.value = platformPrefs.autoUpgradeQuality
        _debridCachedOnly.value = platformPrefs.debridCachedOnly
        _simklToken.value = platformPrefs.simklToken
        _wetrakrKey.value = platformPrefs.wetrakrKey
        _publicmetadbKey.value = platformPrefs.publicmetadbKey
        _mdblistKey.value = platformPrefs.mdblistKey
        _customTmdbApiKey.value = platformPrefs.customTmdbApiKey
        _traktUser.value = trakt.user()
        refreshCatalog()
    }

    override fun onCleared() {
        super.onCleared()
        lanBackup.stop()
    }
}
