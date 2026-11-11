package com.joabtv.app.ui.settings

import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.ViewModule
import com.joabtv.app.data.local.SubLangs
import androidx.compose.foundation.background
import com.joabtv.app.ui.common.topInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.ImportPart
import com.joabtv.app.ui.collections.CollectionsViewModel
import com.joabtv.app.ui.collections.countOf
import com.joabtv.app.data.local.PlatformMode
import com.joabtv.app.ui.theme.*

@Composable
fun SettingsScreen(
    onOpenAddons: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    onOpenHomeCustomize: () -> Unit = {},
    onOpenDebrid: () -> Unit = {},
    onOpenAccounts: () -> Unit = {},
    onOpenLive: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    onOpenAddonStore: () -> Unit = onOpenAddons,
    onBack: () -> Unit,
    onPlatformChanged: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val currentMode by viewModel.platformMode.collectAsStateWithLifecycle()
    val deviceLayout = com.joabtv.app.ui.common.currentDeviceLayout(currentMode)
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val pid by viewModel.profileId.collectAsStateWithLifecycle()
    val me = profiles.firstOrNull { it.id == pid } ?: profiles.first()
    val sourceStatus by viewModel.sourceStatus.collectAsStateWithLifecycle()
    val checkingHealth by viewModel.checkingHealth.collectAsStateWithLifecycle()
    // Verifica automaticamente ao abrir as Configurações (uma vez).
    LaunchedEffect(Unit) { if (sourceStatus.isEmpty()) viewModel.checkSourcesHealth() }
    val pad = com.joabtv.app.ui.common.pagePadding()

    // ── Seu catálogo. Cinco cartões vivos no topo, com o estado e a ação da vez ──
    val accVm: AccountsViewModel = hiltViewModel()
    val colVm: CollectionsViewModel = hiltViewModel()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val accSt by accVm.ui.collectAsStateWithLifecycle()
    val colSt by colVm.ui.collectAsStateWithLifecycle()
    val isTv = com.joabtv.app.ui.common.LocalIsTv.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var panel by remember { mutableStateOf<AccountPanel?>(null) }
    var heroOpen by remember { mutableStateOf(false) }
    var linkOpen by remember { mutableStateOf(false) }
    // Botão que abriu outra tela ("cartão/botão"). Na volta o foco já nasce nele (regra 1 do foco inicial).
    var opened by rememberSaveable { mutableStateOf("") }
    var lastFocus by remember { mutableStateOf("") }
    val cards = remember(catalog, colSt.busy) { catalogCards(catalog, linkBusy = colSt.busy == "url" || colSt.busy == "paste") }
    val reqs = remember { HashMap<String, FocusRequester>() }
    fun req(card: String, act: String) = reqs.getOrPut("$card/$act") { FocusRequester() }
    /** Foco no botão pedido, ou no primário do mesmo cartão quando o botão sumiu (o estado mudou). */
    fun refocus(key: String) {
        val card = key.substringBefore('/')
        if (runCatching { req(card, key.substringAfter('/')).requestFocus() }.isFailure) runCatching { req(card, "primary").requestFocus() }
    }
    val onCardAct: (String, String) -> Unit = { card, act ->
        lastFocus = "$card/$act"
        fun open(go: () -> Unit) { opened = lastFocus; go() }
        when (card) {
            "home" -> if (act == "hero") heroOpen = true else open(onOpenHomeCustomize)
            Account.NUVIO.key, Account.STREMIO.key -> accountAct(accVm, Account.of(card)!!, act, { panel = it })
            "addons" -> if (act == "explore") open(onOpenAddonStore) else open(onOpenAddons)
            "cols" -> when (act) {
                "open" -> open(onOpenCollections)
                "link" -> linkOpen = true
                "login" -> accountAct(accVm, Account.NUVIO, "login", { panel = it }, ImportPart.COLLECTIONS)
                "pull" -> accVm.runAndTell(Account.NUVIO, listOf(ImportPart.COLLECTIONS))
            }
            "kids" -> open(onOpenProfiles)
        }
    }
    // Foco inicial na TV, na primeira regra que valer (3.1 da especificação). O celular não puxa foco.
    LaunchedEffect(Unit) {
        val key = firstFocusKey(cards, opened)
        android.util.Log.d("JOTVFOCUS", "effect key=$key cards=${cards.map { it.key }} nuvio=${catalog.nuvio.signedIn} t=${System.currentTimeMillis() % 100000}") // DBGTMP
        opened = ""
        if (isTv) {
            lastFocus = key
            // Os botões ainda estão sendo medidos. Tenta de novo por um instante e cai no primário do cartão se o botão não existir mais.
            repeat(40) { i ->
                val act = if (i < 12) key.substringAfter('/') else "primary"
                if (runCatching { req(key.substringBefore('/'), act).requestFocus() }.isSuccess) return@LaunchedEffect
                kotlinx.coroutines.delay(50)
            }
        }
    }
    // Ao voltar de um painel ou diálogo, o foco volta ao botão que abriu. Também puxa de volta quando uma tela
    // aberta daqui acabou e o catálogo mudou (o que ela trouxe aparece nos cartões).
    val overlayOpen = panel != null || accSt.login != null || heroOpen || linkOpen
    var wasOpen by remember { mutableStateOf(false) }
    LaunchedEffect(overlayOpen) {
        if (wasOpen && !overlayOpen && isTv && lastFocus.isNotEmpty()) {
            kotlinx.coroutines.delay(120)
            refocus(lastFocus)
        }
        wasOpen = overlayOpen
    }
    // O que o perfil viu em Minha Início muda fora dos fluxos. Ao voltar de outra tela os cartões se refazem.
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refreshCatalog()
        onPauseOrDispose { }
    }

    Column(Modifier.fillMaxSize().background(Background).topInsetsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = pad, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Na TV quem volta é o Voltar do controle. O botão sai do caminho do foco.
            if (!com.joabtv.app.ui.common.LocalIsTv.current) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = TextPrimary)
            }
            com.joabtv.app.ui.common.PageHeader("Configurações", Modifier.weight(1f), sub = "Sua Início, contas, reprodução e aparelho")
        }
        // Resumo à direita em tela larga, embaixo do conteúdo em tela estreita (.set-summary).
        val summary: @Composable () -> Unit = {
            SettingsSummary(
                profile = me.name + if (me.kids) " (infantil)" else "",
                catalog = catalog,
                tmdbOwn = viewModel.customTmdbApiKey.collectAsStateWithLifecycle().value.isNotBlank(),
                online = sourceStatus.count { it.online },
                total = sourceStatus.size,
                checking = checkingHealth,
                platform = when (currentMode) {
                    PlatformMode.TV -> "TV"
                    PlatformMode.PHONE -> "Toque · ${deviceLayout.label}"
                    PlatformMode.AUTO -> "Automático · ${deviceLayout.label}"
                },
                trakt = if (viewModel.traktConfigured) viewModel.traktUser.collectAsStateWithLifecycle().value else null,
                traktConfigured = viewModel.traktConfigured,
            )
        }
        com.joabtv.app.ui.common.ContentWithSummary(summary) { below ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = pad).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Seu catálogo. Cinco cartões na ordem fixa (Minha Início, Nuvio, Stremio, Addons, Coleções).
                SettingsSection("Seu catálogo")
                if (!me.kids && !catalog.nuvio.signedIn && !catalog.stremio.signedIn && catalog.addons.total == 0) Text(
                    "Comece trazendo suas coisas de uma conta ou explore addons.",
                    color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(start = 4.dp),
                )
                cards.forEachIndexed { i, c ->
                    LiveCard(
                        c, requesterOf = { req(c.key, it) }, primaryRequester = req(c.key, "primary"),
                        onAct = { onCardAct(c.key, it) },
                        upTo = cards.getOrNull(i - 1)?.let { req(it.key, "primary") },
                        downTo = cards.getOrNull(i + 1)?.let { req(it.key, "primary") },
                        onFocused = { android.util.Log.d("JOTVFOCUS", "focus ${c.key}/$it t=${System.currentTimeMillis() % 100000}"); lastFocus = "${c.key}/$it" }, // DBGTMP
                    )
                }

                // Perfil ativo. Tocar abre o "Quem está assistindo?" para trocar ou editar.
                SettingsSection("Perfil")
                com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.Person,
                    title = me.name,
                    sub = (if (me.kids) "Infantil" else "Perfil") + " · trocar ou editar perfis",
                    // Cima volta ao primário do último cartão, como Baixo saiu dele.
                    modifier = Modifier.focusProperties { up = req(cards.last().key, "primary") },
                    onClick = onOpenProfiles,
                )
                // PIN dos pais. Só mexe quem já está fora do infantil (sair do infantil já pede
                // o PIN quando existe um, então chegar aqui com infantil ativo não rola).
                if (!me.kids) {
                    var showSetPin by remember { mutableStateOf(false) }
                    val hasPin = viewModel.hasParentalPin
                    com.joabtv.app.ui.common.SettingsRow(
                        icon = Icons.Default.Lock,
                        title = if (hasPin) "Trocar PIN dos pais" else "Criar PIN dos pais",
                        sub = "Trava sair do infantil e abrir Addons, TMDB e Aparelho com o infantil ativo",
                        onClick = { showSetPin = true },
                    )
                    if (showSetPin) SetPinDialog(
                        title = if (hasPin) "Trocar PIN dos pais" else "Criar PIN dos pais",
                        onSet = { pin -> viewModel.setParentalPin(pin); showSetPin = false },
                        onDismiss = { showSetPin = false },
                    )
                }

                if (viewModel.traktConfigured) SettingsCard {
                    Text("Trakt", color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    TraktSection(viewModel)
                }

                // Reprodução do perfil ativo. Ordena as fontes e limita a qualidade escolhida no Assistir.
                SettingsSection("Reprodução")
                val audio by viewModel.audioPref.collectAsStateWithLifecycle()
                val maxQ by viewModel.maxQuality.collectAsStateWithLifecycle()
                val heroTrailer by viewModel.heroTrailer.collectAsStateWithLifecycle()
                SettingsCard {
                    PrefRow("Áudio preferido", listOf("any" to "Qualquer", "dub" to "Dublado", "leg" to "Legendado"), audio, viewModel::setAudio)
                    Spacer(Modifier.height(14.dp))
                    PrefRow("Qualidade máxima", listOf(0 to "Sem limite", 2160 to "4K", 1080 to "1080p", 720 to "720p"), maxQ, viewModel::setMaxQuality)
                }
                com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.Movie,
                    title = "Trailer no destaque",
                    sub = "Toca sem som depois de 5 s parado num título da Início",
                    // A linha liga e desliga. O Switch fica fora do caminho do controle.
                    trailing = { Switch(checked = heroTrailer, onCheckedChange = { viewModel.setHeroTrailer(it) }, modifier = Modifier.focusProperties { canFocus = false }) },
                    onClick = { viewModel.setHeroTrailer(!heroTrailer) },
                )
                // Legenda do perfil. Ligada ao abrir e o idioma em que ela liga sozinha no player.
                val subOn by viewModel.subOn.collectAsStateWithLifecycle()
                val subLang by viewModel.subLang.collectAsStateWithLifecycle()
                var pickLang by remember { mutableStateOf(false) }
                com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.ClosedCaption,
                    title = "Legenda ao abrir",
                    sub = if (subOn) "Liga sozinha em ${SubLangs.label(subLang)} quando o título tiver" else "Desligada. Dá para ligar no player, em Legendas",
                    trailing = { Switch(checked = subOn, onCheckedChange = { viewModel.setSubOn(it) }, modifier = Modifier.focusProperties { canFocus = false }) },
                    onClick = { viewModel.setSubOn(!subOn) },
                )
                com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.Translate,
                    title = "Idioma da legenda",
                    sub = SubLangs.label(subLang) + ". Também aparece primeiro na lista do player",
                    onClick = { pickLang = true },
                )
                if (pickLang) SubLangDialog(subLang, onPick = { viewModel.setSubLang(it); pickLang = false }, onDismiss = { pickLang = false })
                val subSize by viewModel.subSize.collectAsStateWithLifecycle()
                val subBg by viewModel.subBg.collectAsStateWithLifecycle()
                SettingsCard {
                    PrefRow("Tamanho da legenda", com.joabtv.app.ui.movies.SUB_SIZES, subSize, viewModel::setSubSize)
                    Spacer(Modifier.height(14.dp))
                    PrefRow("Fundo da legenda", listOf(true to "Com fundo", false to "Sem fundo"), subBg, viewModel::setSubBg)
                    Text(
                        "Também muda dentro do player, em Legendas.",
                        color = TextTertiary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp),
                    )
                }

                SettingsSection("Aparência")
                val appearance by viewModel.appearance.collectAsStateWithLifecycle()
                AppearanceSettings(appearance, viewModel::setTheme, viewModel::setMotion, viewModel::setMenu, viewModel::setUiSize)

                // Addons, Aparelho e TMDB somem no perfil infantil — só quem já passou pelo PIN
                // pra sair do infantil mexe nisso (ver ProfilesScreen).
                if (!me.kids) {
                SettingsSection("TV ao vivo")
                com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.LiveTv,
                    iconTint = Accent,
                    title = "Canais ao vivo",
                    sub = "Listas M3U e contas Xtream, com guia de programação.",
                    onClick = onOpenLive,
                )
                SettingsSection("Fontes de vídeo")
                com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.Bolt,
                    iconTint = Accent,
                    title = "Debrid",
                    sub = "Real-Debrid, TorBox ou Premiumize. Torrent toca na hora por link direto.",
                    onClick = onOpenDebrid,
                )
                }

                // Status das fontes. Chama a rede, então gira e trava enquanto verifica.
                SettingsSection("Diagnóstico")
                val frameMeter by viewModel.frameMeter.collectAsStateWithLifecycle()
                com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.Refresh,
                    title = "Medidor de quadros",
                    sub = "Mostra no canto da tela os quadros por segundo e de onde vem o tempo, para medir no aparelho",
                    trailing = { Switch(checked = frameMeter, onCheckedChange = { viewModel.setFrameMeter(it) }, modifier = Modifier.focusProperties { canFocus = false }) },
                    onClick = { viewModel.setFrameMeter(!frameMeter) },
                )
                com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.Refresh,
                    title = if (checkingHealth) "Verificando…" else "Verificar fontes agora",
                    sub = "Confere se o TMDB e os addons estão online (DNS Cloudflare)",
                    busy = checkingHealth,
                    trailing = null,
                    onClick = { viewModel.checkSourcesHealth() },
                )
                if (sourceStatus.isNotEmpty()) SettingsCard {
                    sourceStatus.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(10.dp).clip(RoundedCornerShape(999.dp)).background(if (s.online) Ok else Danger))
                            Text(s.name, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                            Text(
                                if (s.online) "${s.latencyMs} ms" else s.detail,
                                color = if (s.online) TextTertiary else DangerText, fontSize = 13.sp, maxLines = 1,
                            )
                        }
                    }
                }

                // Plataforma
                if (!me.kids) {
                SettingsSection("Aparelho")
                Text(
                    "Layout atual ${deviceLayout.label}. O modo automático reconhece TV e adapta celular e tablet ao espaço disponível.",
                    fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp,
                )
                PlatformOption(
                    selected = currentMode == PlatformMode.AUTO,
                    title = "Automático",
                    subtitle = "Reconhece TV, tablet e celular e acompanha o tamanho da janela",
                    icon = Icons.Default.Devices,
                    onClick = { viewModel.setPlatformMode(PlatformMode.AUTO); onPlatformChanged() },
                )
                PlatformOption(
                    selected = currentMode == PlatformMode.TV,
                    title = "Forçar TV",
                    subtitle = "Layout de controle remoto com menu lateral",
                    icon = Icons.Default.Tv,
                    onClick = { viewModel.setPlatformMode(PlatformMode.TV); onPlatformChanged() },
                )
                PlatformOption(
                    selected = currentMode == PlatformMode.PHONE,
                    title = "Celular ou tablet",
                    subtitle = "Layout de toque adaptado ao espaço disponível",
                    icon = Icons.Default.PhoneAndroid,
                    onClick = { viewModel.setPlatformMode(PlatformMode.PHONE); onPlatformChanged() },
                )

                SettingsSection("Dados de filmes")
                val customTmdbKey by viewModel.customTmdbApiKey.collectAsStateWithLifecycle()
                var tmdbDraft by remember(customTmdbKey) { mutableStateOf(customTmdbKey) }
                var tmdbVisible by remember(customTmdbKey) { mutableStateOf(false) }
                Text(
                    if (customTmdbKey.isBlank()) "Chave padrão do app em uso. Se preferir, informe sua própria chave abaixo."
                    else "Chave pessoal em uso. Você pode trocar a chave ou restaurar o padrão do app.",
                    fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.joabtv.app.ui.common.TvTextFieldGate(Modifier.weight(1f)) { gate ->
                        OutlinedTextField(
                            value = tmdbDraft,
                            onValueChange = { tmdbDraft = it },
                            label = { Text("Sua chave TMDB (opcional)") },
                            placeholder = { Text("Cole sua chave aqui", color = TextTertiary) },
                            visualTransformation = if (tmdbVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = gate,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = Border,
                                focusedContainerColor = Surface2,
                                unfocusedContainerColor = Surface2,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedLabelColor = Accent,
                                unfocusedLabelColor = TextTertiary,
                                cursorColor = Accent,
                            ),
                        )
                    }
                    IconButton(
                        onClick = { tmdbVisible = !tmdbVisible }, enabled = tmdbDraft.isNotEmpty(),
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            if (tmdbVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (tmdbVisible) "Ocultar chave pessoal" else "Mostrar chave pessoal",
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    com.joabtv.app.ui.common.JoabButton(
                        "Salvar chave", onClick = {
                            viewModel.setTmdbApiKey(tmdbDraft)
                            tmdbDraft = viewModel.customTmdbApiKey.value
                            tmdbVisible = false
                        },
                        enabled = tmdbDraft.isNotBlank() && tmdbDraft.trim() != customTmdbKey,
                        compact = true, tone = com.joabtv.app.ui.common.ButtonTone.PRIMARY,
                    )
                    if (customTmdbKey.isNotBlank()) com.joabtv.app.ui.common.JoabButton(
                        "Usar chave padrão", onClick = { viewModel.setTmdbApiKey(""); tmdbDraft = ""; tmdbVisible = false },
                        compact = true,
                    )
                }
                } // !me.kids (Aparelho + TMDB)

                SettingsSection("Biblioteca")
                var confirmClear by remember { mutableStateOf(false) }
                val context = androidx.compose.ui.platform.LocalContext.current
                com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.History,
                    title = "Limpar Continuar assistindo",
                    sub = "Apaga posições salvas de filmes e séries. Minha Lista continua",
                    onClick = { confirmClear = true },
                )
                if (confirmClear) AlertDialog(
                    onDismissRequest = { confirmClear = false },
                    containerColor = BackgroundCard,
                    title = { Text("Limpar Continuar assistindo?", color = TextPrimary, fontWeight = FontWeight.ExtraBold) },
                    text = { Text("As posições salvas somem. Minha Lista continua.", color = TextSecondary) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.clearContinueWatching()
                            confirmClear = false
                            android.widget.Toast.makeText(context, "Histórico limpo", android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text("Limpar", color = DangerText) }
                    },
                    dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar", color = TextSecondary) } },
                )
                below?.let { Spacer(Modifier.height(18.dp)); it() }
            }
        }
    }

    // Diálogo de entrada, painéis Mais e O que veio, destaque e link das coleções.
    AccountOverlays(accVm, panel, { panel = it }, onOpenHome = { accVm.openHomeOnNew(); opened = "home/organize"; onOpenHomeCustomize() })
    if (heroOpen) {
        val cur by viewModel.heroSource.collectAsStateWithLifecycle()
        val options = remember { listOf("" to "Automático") + viewModel.heroChoices() }
        LiveChoice(
            "Destaque da Início", "O banner grande do topo",
            options.map { (k, label) -> Triple(label, if (k.isEmpty()) "Em alta das sugestões, ou a primeira fileira sem elas" else null, k == cur) },
            onPick = { i -> viewModel.setHeroSource(options[i].first); heroOpen = false },
            onDismiss = { heroOpen = false },
        )
    }
    if (linkOpen) {
        com.joabtv.app.ui.collections.CollectionImportDialog(
            st = colSt, initialMode = "url",
            onPaste = colVm::importText, onUrl = colVm::importUrl,
            onNuvio = {
                linkOpen = false
                if (colSt.nuvioLogged) accVm.runAndTell(Account.NUVIO, listOf(ImportPart.COLLECTIONS))
                else accountAct(accVm, Account.NUVIO, "login", { panel = it }, ImportPart.COLLECTIONS)
            },
            onDismiss = { linkOpen = false },
        )
        // Trouxe, fecha o diálogo e avisa. O erro fica no diálogo.
        LaunchedEffect(colSt.message) {
            colSt.message?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show(); colVm.clearMessage(); linkOpen = false }
        }
    }
}

/** Resumo das configurações (.set-summary). Logo, versão, o estado do catálogo e do aparelho. */
@Composable
private fun SettingsSummary(
    profile: String,
    catalog: CatalogState,
    tmdbOwn: Boolean,
    online: Int,
    total: Int,
    checking: Boolean,
    platform: String,
    trakt: String?,
    traktConfigured: Boolean,
) {
    fun tone(t: TagTone?) = when (t) { TagTone.ON -> Ok; TagTone.WARN -> Gold; TagTone.BAD -> DangerText; null -> TextPrimary }
    com.joabtv.app.ui.common.SummaryCard {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(com.joabtv.app.R.drawable.jotv_logo), "JOTV",
                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)),
            )
            Column(Modifier.padding(start = 14.dp)) {
                Text("JOTV", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Text("Versão ${com.joabtv.app.BuildConfig.VERSION_NAME}", color = TextTertiary, fontSize = 13.sp)
            }
        }
        if (!catalog.kids) {
            com.joabtv.app.ui.common.Eyebrow("Seu catálogo", Modifier.padding(bottom = 6.dp))
            com.joabtv.app.ui.common.SummaryRow("Fileiras na Início", catalog.home.rows.toString())
            Account.entries.forEach { a ->
                val (text, t) = accountShort(a, catalog.snap(a), catalog.log[a], catalog.running[a.key])
                com.joabtv.app.ui.common.SummaryRow(a.label, text, tone(t))
            }
            // Vermelho só quando há addon e todos estão parados.
            val ad = catalog.addons
            com.joabtv.app.ui.common.SummaryRow(
                "Addons", if (ad.total == 0) "Nenhum" else countOf(ad.active, "ativo", "ativos"),
                if (ad.total > 0 && ad.active == 0) DangerText else TextPrimary,
            )
            com.joabtv.app.ui.common.SummaryRow("Coleções", if (catalog.cols.count == 0) "Nenhuma" else catalog.cols.count.toString())
            Spacer(Modifier.height(14.dp))
        }
        com.joabtv.app.ui.common.SummaryRow("Perfil", profile)
        com.joabtv.app.ui.common.SummaryRow(
            "Fontes online",
            if (checking) "Verificando…" else if (total == 0) "Sem teste" else "$online de $total",
            if (checking || total == 0) TextPrimary else if (online == total) Ok else DangerText,
        )
        if (!catalog.kids) com.joabtv.app.ui.common.SummaryRow("Chave TMDB", if (tmdbOwn) "Própria" else "Padrão")
        com.joabtv.app.ui.common.SummaryRow("Aparelho", platform)
        if (traktConfigured) com.joabtv.app.ui.common.SummaryRow("Trakt", trakt?.ifBlank { "Conectado" } ?: "Desconectado", if (trakt != null) Ok else TextPrimary)
        Text(
            "As escolhas de reprodução valem só para o perfil ativo.",
            color = TextTertiary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SettingsSection(text: String) = com.joabtv.app.ui.common.SettingsSection(text)

/** Bloco com fundo de cartão para agrupar controles que não são uma linha só. */
@Composable
private fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(BackgroundCard2).padding(16.dp),
        content = content,
    )
}

@Composable
internal fun AppearanceSettings(
    appearance: com.joabtv.app.data.local.AppearancePreferences,
    onTheme: (com.joabtv.app.data.local.AppTheme) -> Unit,
    onMotion: (com.joabtv.app.data.local.InterfaceMotion) -> Unit,
    onMenu: ((com.joabtv.app.data.local.MenuStyle) -> Unit)? = null,
    onSize: ((com.joabtv.app.data.local.InterfaceSize) -> Unit)? = null,
) {
    SettingsCard {
        PrefRow("Tema", com.joabtv.app.data.local.AppTheme.entries.map { it to it.label }, appearance.theme, onTheme, tonal = true)
        Spacer(Modifier.height(18.dp))
        PrefRow("Movimento da interface", com.joabtv.app.data.local.InterfaceMotion.entries.map { it to it.label }, appearance.motion, onMotion, tonal = true)
        Text(
            "Suave usa transições curtas. Reduzido torna a navegação mais direta. A opção Remover animações do aparelho tem prioridade.",
            color = TextTertiary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp),
        )
        // Só a TV e o desktop têm menu para escolher. O celular no toque segue com a barra embaixo.
        if (onMenu != null && com.joabtv.app.ui.common.LocalIsTv.current) {
            Spacer(Modifier.height(18.dp))
            PrefRow("Menu", com.joabtv.app.data.local.MenuStyle.entries.map { it to it.label }, appearance.menu, onMenu, tonal = true)
            Text(
                "Cápsula na lateral, barra no topo da tela ou lateral escondida.",
                color = TextTertiary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
        // O tamanho vale só na TV, onde tudo é medido pela largura da tela. A linha da tela ajuda a achar a causa quando alguém relata texto pequeno.
        if (onSize != null && com.joabtv.app.ui.common.LocalIsTv.current) {
            val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
            Spacer(Modifier.height(18.dp))
            PrefRow("Tamanho da interface", com.joabtv.app.data.local.InterfaceSize.entries.map { it to it.label }, appearance.uiSize, onSize, tonal = true)
            Text(
                "Aumenta o texto, os cartões e as margens juntos. Em TV pequena ou vista de longe, use Grande. " +
                    "Esta tela tem ${metrics.widthPixels} x ${metrics.heightPixels} pixels a ${metrics.densityDpi} dpi.",
                color = TextTertiary, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/**
 * Conta do Trakt do perfil ativo. Conectar mostra o código para digitar em trakt.tv/activate.
 * Botão de rede marca na hora, gira "Salvando…" e trava o segundo toque até a resposta.
 */
@Composable
private fun TraktSection(viewModel: SettingsViewModel) {
    val user by viewModel.traktUser.collectAsStateWithLifecycle()
    val busy by viewModel.traktBusy.collectAsStateWithLifecycle()
    val code by viewModel.traktCode.collectAsStateWithLifecycle()
    Text(
        if (user == null) "Junta a Minha Lista, o ponto onde parou e os episódios vistos com o celular, a TV e o site."
        else "Conectado${if (user.isNullOrBlank()) "" else " como $user"}. Sincroniza ao abrir a Início.",
        fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp,
    )
    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BusyButton(
            label = if (user == null) "Conectar" else "Sincronizar agora",
            busyLabel = busy,
            onClick = { if (user == null) viewModel.traktConnect() else viewModel.traktSync() },
        )
        if (user != null && busy == null) {
            com.joabtv.app.ui.common.JoabButton("Desconectar", viewModel::traktLogout, compact = true)
        }
    }
    code?.let { c ->
        AlertDialog(
            onDismissRequest = viewModel::traktCancel,
            containerColor = BackgroundCard,
            title = { Text("Conectar ao Trakt", color = TextPrimary) },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text("Abra no celular ou no computador", color = TextSecondary, fontSize = 13.sp)
                    Text(c.url.removePrefix("https://"), color = Accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(14.dp))
                    Text("e digite o código", color = TextSecondary, fontSize = 13.sp)
                    Text(c.userCode, color = TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 4.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Accent)
                        Text("Aguardando confirmação…", color = TextTertiary, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = viewModel::traktCancel) { Text("Cancelar", color = TextSecondary) } },
        )
    }
}

/** Botão de chamada de rede. Com [busyLabel] gira, mostra o texto e não aceita outro toque. */
@Composable
private fun BusyButton(label: String, busyLabel: String?, onClick: () -> Unit) {
    com.joabtv.app.ui.common.JoabButton(
        label, onClick, icon = Icons.Default.Sync, tone = com.joabtv.app.ui.common.ButtonTone.PRIMARY,
        busy = busyLabel != null, busyLabel = busyLabel ?: "", compact = true,
    )
}

/** Lista dos idiomas de legenda. Na TV o foco já começa no idioma atual. */
@Composable
private fun SubLangDialog(current: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val langs = SubLangs.all
    val start = langs.indexOfFirst { it.key == current }.coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    val isTv = com.joabtv.app.ui.common.LocalIsTv.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundCard,
        title = { Text("Idioma da legenda", color = TextPrimary, fontWeight = FontWeight.ExtraBold) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), state = list, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(langs, key = { _, l -> l.key }) { i, l ->
                    val fr = remember { FocusRequester() }
                    LangOption(l.label, l.key == current, Modifier.focusRequester(fr)) { onPick(l.key) }
                    // No celular o toque basta. Foco puxado ali acenderia a borda sem ninguém pedir.
                    if (isTv && i == start) LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar", color = TextSecondary) } },
    )
}

@Composable
private fun LangOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(if (focused || selected) AccentSoft else Color.Transparent)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = TextPrimary, fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f))
        if (selected) Icon(Icons.Default.Check, "Selecionado", tint = Accent)
    }
}

/** Uma preferência de escolha única em pílulas. Salva na hora (é local, sem rede). */
@Composable
private fun <T> PrefRow(title: String, options: List<Pair<T, String>>, value: T, onPick: (T) -> Unit, tonal: Boolean = false) {
    Text(title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            com.joabtv.app.ui.common.FilterChipPill(label, v == value, onClick = { onPick(v) }, tonal = tonal)
        }
    }
}

/** Opção de aparelho. Selecionada em azul suave com a borda azul e o check. */
@Composable
private fun PlatformOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    com.joabtv.app.ui.common.SettingsRow(
        icon = icon,
        title = title,
        sub = subtitle,
        iconTint = if (selected) Accent else TextSecondary,
        modifier = if (selected) Modifier.border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp)) else Modifier,
        trailing = if (selected) ({ Icon(Icons.Default.CheckCircle, "Selecionado", tint = Accent) }) else null,
        onClick = onClick,
    )
}
