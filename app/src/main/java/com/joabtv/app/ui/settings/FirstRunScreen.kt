package com.joabtv.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.local.PlatformMode
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.local.Profile
import com.joabtv.app.ui.common.ButtonTone
import com.joabtv.app.ui.common.ContentWithSummary
import com.joabtv.app.ui.common.JoabButton
import com.joabtv.app.ui.common.LocalIsTv
import com.joabtv.app.ui.common.SettingsRow
import com.joabtv.app.ui.common.SummaryCard
import com.joabtv.app.ui.common.SummaryRow
import com.joabtv.app.ui.common.currentDeviceLayout
import com.joabtv.app.ui.common.pagePadding
import com.joabtv.app.ui.common.topInsetsPadding
import com.joabtv.app.ui.theme.Accent
import com.joabtv.app.ui.theme.Background
import com.joabtv.app.ui.theme.BackgroundCard2
import com.joabtv.app.ui.theme.Border
import com.joabtv.app.ui.theme.Inter
import com.joabtv.app.ui.theme.Ok
import com.joabtv.app.ui.theme.Surface2
import com.joabtv.app.ui.theme.TextPrimary
import com.joabtv.app.ui.theme.TextSecondary
import com.joabtv.app.ui.theme.TextTertiary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class FirstRunViewModel @Inject constructor(private val prefs: PlatformPrefs) : ViewModel() {
    /** Perfil que está sendo montado. Acompanha o que a importação da conta trouxer (nome e foto). */
    val profile: StateFlow<Profile> = combine(prefs.profiles, prefs.profileId) { list, id -> list.firstOrNull { it.id == id } ?: list.first() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, prefs.profile)
    val initialMode: PlatformMode get() = prefs.platformMode

    /** Grava nome, foto e plataforma escolhidos e marca a primeira abertura como feita. */
    fun confirm(name: String, avatar: String, mode: PlatformMode) {
        val p = prefs.profile
        val clean = name.trim().take(20)
        val next = p.copy(name = clean.ifBlank { p.name }, avatar = avatar)
        if (next != p) prefs.updateProfile(next)
        prefs.platformMode = mode
        prefs.onboarded = true
    }
}

private const val STEPS = 6

private val STEP_TEXT = listOf(
    "Já usa o Nuvio ou o Stremio?" to "Entre para trazer nome, foto, addons, plugins, Minha Lista e o que você estava assistindo. Depois o JOTV mantém o histórico igual nos dois.",
    "Como podemos te chamar?" to "O nome e a foto do seu perfil. Cada perfil tem a própria lista e o próprio progresso.",
    "Tem Real-Debrid, TorBox ou Premiumize?" to "Com um serviço de debrid os torrents tocam na hora, como link direto.",
    "Quer acompanhar no Trakt?" to "Marca o que você assiste e traz o que você já viu. Outros serviços ficam em Configurações.",
    "Como você quer ver o JOTV?" to "Tema e movimento da interface. Dá para mudar depois em Configurações.",
    "Como o vídeo deve tocar?" to "O que o player escolhe sozinho. Tudo muda depois em Configurações.",
)

/**
 * Primeira abertura do app, em seis passos que dá para pular (menos o nome). Conta Nuvio ou Stremio, perfil com nome
 * e foto, debrid, Trakt, aparência e reprodução. A conta vem primeiro porque a importação já traz o nome e a foto do
 * perfil, e o passo do perfil mostra o que veio para a pessoa ajustar. Só aparece uma vez (onboarded=false).
 */
@Composable
fun FirstRunScreen(onDone: () -> Unit, vm: FirstRunViewModel = hiltViewModel()) {
    val tv = LocalIsTv.current
    val pad = pagePadding()
    val profile by vm.profile.collectAsStateWithLifecycle()
    val accountsVm: AccountsViewModel = hiltViewModel()
    val debridVm: DebridViewModel = hiltViewModel()
    val settingsVm: SettingsViewModel = hiltViewModel()
    val debrid by debridVm.ui.collectAsStateWithLifecycle()
    val accounts by accountsVm.ui.collectAsStateWithLifecycle()
    val trakt by settingsVm.traktUser.collectAsStateWithLifecycle()
    val appearance by settingsVm.appearance.collectAsStateWithLifecycle()
    val audio by settingsVm.audioPref.collectAsStateWithLifecycle()
    val showAddons by settingsVm.menuAddons.collectAsStateWithLifecycle()

    var step by rememberSaveable { mutableIntStateOf(0) }
    var name by rememberSaveable { mutableStateOf(profile.name) }
    var avatar by rememberSaveable { mutableStateOf(profile.avatar) }
    var mode by remember { mutableStateOf(vm.initialMode) }
    var naming by remember { mutableStateOf(false) }
    var debridOpen by rememberSaveable { mutableStateOf(false) }
    val primary = remember { FocusRequester() }
    // A importação da conta troca nome e foto do perfil. O formulário acompanha o que chegou.
    LaunchedEffect(profile.name, profile.avatar) { name = profile.name; avatar = profile.avatar }
    LaunchedEffect(step, debridOpen, naming) { if (tv && !debridOpen && !naming) runCatching { primary.requestFocus() } }
    BackHandler(enabled = step > 0 && !debridOpen && !naming) { step-- }

    if (debridOpen) {
        BackHandler { debridOpen = false }
        DebridScreen(onBack = { debridOpen = false }, viewModel = debridVm)
        return
    }
    if (naming) {
        com.joabtv.app.ui.common.TvTextPrompt(
            title = "Como podemos te chamar?",
            initial = name,
            okLabel = "Continuar",
            onDone = { name = it.take(20); naming = false },
            onDismiss = { naming = false },
        )
        return
    }

    val last = step == STEPS - 1
    val canContinue = step != 1 || name.isNotBlank()
    fun next() {
        if (last) { vm.confirm(name, avatar, mode); onDone() } else step++
    }

    Column(Modifier.fillMaxSize().background(Background).topInsetsPadding()) {
        WizardHeader(step, STEP_TEXT[step].first, STEP_TEXT[step].second, pad)
        ContentWithSummary(
            summary = {
                SummaryCard {
                    Text("Seu JOTV", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Inter)
                    Spacer(Modifier.height(10.dp))
                    SummaryRow("Perfil", name.ifBlank { "Sem nome" })
                    SummaryRow("Conta", Account.entries.filter { accounts.snap(it).signedIn }.joinToString(" e ") { it.label }.ifBlank { "Nenhuma" })
                    SummaryRow("Debrid", debrid.accounts.joinToString(" · ") { it.provider.short }.ifBlank { "Nenhum" })
                    SummaryRow("Trakt", if (trakt == null) "Não conectado" else trakt.orEmpty().ifBlank { "Conectado" })
                    SummaryRow("Tema", appearance.theme.label)
                    SummaryRow("Áudio", when (audio) { "dub" -> "Dublado"; "leg" -> "Legendado"; else -> "Qualquer" })
                }
            },
            modifier = Modifier.weight(1f),
        ) { below ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pad).padding(top = 8.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (step) {
                    0 -> AccountStep(accountsVm)
                    1 -> ProfileStep(
                        name = name, onName = { name = it.take(20) }, onRename = { naming = true },
                        profile = profile.copy(name = name.ifBlank { "?" }, avatar = avatar),
                        onAvatar = { avatar = it }, mode = mode, onMode = { mode = it },
                    )
                    2 -> DebridStep(connected = debrid.accounts.joinToString(", ") { it.provider.label }, onOpen = { debridOpen = true })
                    3 -> WizardCard { TraktSection(settingsVm) }
                    4 -> AppearanceSettings(
                        appearance, settingsVm::setTheme, settingsVm::setMotion, settingsVm::setMenu, settingsVm::setUiSize,
                        showAddons, settingsVm::setMenuAddons,
                    )
                    else -> PlaybackStep(settingsVm)
                }
                below?.let { Spacer(Modifier.height(8.dp)); it() }
            }
        }
        WizardBar(
            step = step, canSkip = step != 1 && !last, canContinue = canContinue, last = last,
            onBack = { step-- }, onSkip = { step++ }, onNext = ::next, primary = primary, pad = pad,
        )
    }
    // Entrar com código ou e-mail e a importação aparecem por cima, como nas Configurações.
    AccountOverlays(accountsVm, panel = null, onPanel = {}, onOpenHome = { vm.confirm(name, avatar, mode); onDone() })
}

/** Passo atual, barra com os seis passos e o título. */
@Composable
private fun WizardHeader(step: Int, title: String, sub: String, pad: Dp) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = pad).padding(top = 18.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Passo ${step + 1} de $STEPS", color = TextTertiary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter)
        Row(
            Modifier.fillMaxWidth().semantics { contentDescription = "Passo ${step + 1} de $STEPS" },
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            repeat(STEPS) { i ->
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= step) Accent else Surface2))
            }
        }
        Text(title, color = TextPrimary, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp, fontFamily = Inter)
        Text(sub, color = TextSecondary, fontSize = 15.sp, lineHeight = 21.sp, fontFamily = Inter)
    }
}

/** Voltar, Pular e Continuar. Em tela estreita o Continuar ocupa a largura e os outros ficam embaixo dele. */
@Composable
private fun WizardBar(
    step: Int, canSkip: Boolean, canContinue: Boolean, last: Boolean,
    onBack: () -> Unit, onSkip: () -> Unit, onNext: () -> Unit, primary: FocusRequester, pad: Dp,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().background(Background).navigationBarsPadding().padding(horizontal = pad, vertical = 12.dp)) {
        val label = if (last) "Começar a assistir" else "Continuar"
        if (maxWidth < 420.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JoabButton(label, onNext, Modifier.fillMaxWidth().focusRequester(primary), tone = ButtonTone.PRIMARY, enabled = canContinue)
                if (step > 0 || canSkip) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (step > 0) JoabButton("Voltar", onBack, Modifier.weight(1f), icon = Icons.AutoMirrored.Filled.ArrowBack, compact = true)
                    if (canSkip) JoabButton("Pular", onSkip, Modifier.weight(1f), compact = true)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (step > 0) JoabButton("Voltar", onBack, icon = Icons.AutoMirrored.Filled.ArrowBack, compact = true)
                Spacer(Modifier.weight(1f))
                if (canSkip) JoabButton("Pular", onSkip, compact = true)
                JoabButton(label, onNext, Modifier.focusRequester(primary), tone = ButtonTone.PRIMARY, enabled = canContinue)
            }
        }
    }
}

/** Caixa de conteúdo dos passos (mesma cor dos cartões das Configurações). */
@Composable
private fun WizardCard(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BackgroundCard2).padding(16.dp)) { content() }
}

// ── Passos ──────────────────────────────────────────────────────────────────

@Composable
private fun AccountStep(vm: AccountsViewModel) {
    val st by vm.ui.collectAsStateWithLifecycle()
    Account.entries.forEach { a ->
        val snap = st.snap(a)
        val run = st.running[a.key]
        when {
            run != null -> SettingsRow(Icons.Default.Sync, "Trazendo do ${a.label}", sub = run.text, busy = true, trailing = null)
            // Conectado e ainda sem trazer nada. O toque abre o mesmo Trazer tudo de quando se entra.
            snap.signedIn && st.log[a] == null -> SettingsRow(
                Icons.Default.CheckCircle, "${a.label} conectado", sub = if (a == Account.NUVIO) "Toque para trazer nome, foto, addons, plugins e Minha Lista" else "Toque para trazer addons e Minha Lista",
                iconTint = Ok, onClick = { vm.openImport(a) },
            )
            snap.signedIn -> SettingsRow(
                Icons.Default.CheckCircle, "${a.label} conectado",
                sub = if (st.sync[a]?.enabled == true) "Histórico igual nos dois lados" else snap.email.ifBlank { "Conta conectada" },
                iconTint = Ok, trailing = null,
            )
            else -> SettingsRow(
                Icons.Default.Person, "Entrar com o ${a.label}", sub = a.hint,
                busy = st.busy?.startsWith(a.key) == true, onClick = { vm.startCode(a) },
            )
        }
    }
    Text("Pode pular. Dá para entrar depois em Configurações.", color = TextTertiary, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = Inter)
}

@Composable
private fun ProfileStep(
    name: String, onName: (String) -> Unit, onRename: () -> Unit,
    profile: Profile, onAvatar: (String) -> Unit, mode: PlatformMode, onMode: (PlatformMode) -> Unit,
) {
    val tv = LocalIsTv.current
    val autoLabel = currentDeviceLayout(PlatformMode.AUTO).label
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        ProfileAvatar(profile, 72.dp)
        if (tv) SettingsRow(
            icon = Icons.Default.Edit, title = name.ifBlank { "Sem nome" }, sub = "Toque para editar",
            modifier = Modifier.weight(1f), onClick = onRename,
        ) else OutlinedTextField(
            value = name, onValueChange = onName,
            label = { Text("Nome do perfil") }, singleLine = true, modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent, unfocusedBorderColor = Border,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent,
            ),
        )
    }
    ProfilePhotoChooser(avatar = profile.avatar, profileId = profile.id, onChange = onAvatar)

    Text("Aparelho", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
    Text(
        "O automático reconhece TV, tablet e celular sozinho. Só muda se detectar errado.",
        fontSize = 14.sp, color = TextSecondary, lineHeight = 20.sp, fontFamily = Inter,
    )
    FirstRunPlatformOption(
        selected = mode == PlatformMode.AUTO, title = "Automático", subtitle = "Reconhecido como $autoLabel",
        icon = Icons.Default.Devices, onClick = { onMode(PlatformMode.AUTO) },
    )
    FirstRunPlatformOption(
        selected = mode == PlatformMode.TV, title = "Forçar TV", subtitle = "Layout de controle remoto com menu lateral",
        icon = Icons.Default.Tv, onClick = { onMode(PlatformMode.TV) },
    )
    FirstRunPlatformOption(
        selected = mode == PlatformMode.PHONE, title = "Celular ou tablet", subtitle = "Layout de toque adaptado ao espaço disponível",
        icon = Icons.Default.PhoneAndroid, onClick = { onMode(PlatformMode.PHONE) },
    )
}

@Composable
private fun DebridStep(connected: String, onOpen: () -> Unit) {
    val on = connected.isNotBlank()
    SettingsRow(
        icon = if (on) Icons.Default.CheckCircle else Icons.Default.Bolt,
        iconTint = if (on) Ok else Accent,
        title = if (on) "$connected conectado" else "Conectar um serviço",
        sub = if (on) "Toque para gerenciar" else "Entra com um código no celular ou com a chave da conta",
        onClick = onOpen,
    )
    Text("Pode pular. Dá para conectar depois em Configurações, em Debrid.", color = TextTertiary, fontSize = 13.sp, lineHeight = 19.sp, fontFamily = Inter)
}

@Composable
private fun PlaybackStep(vm: SettingsViewModel) {
    val audio by vm.audioPref.collectAsStateWithLifecycle()
    val maxQ by vm.maxQuality.collectAsStateWithLifecycle()
    val subOn by vm.subOn.collectAsStateWithLifecycle()
    WizardCard {
        PrefRow("Áudio preferido", listOf("any" to "Qualquer", "dub" to "Dublado", "leg" to "Legendado"), audio, vm::setAudio, tonal = true)
        Spacer(Modifier.height(18.dp))
        PrefRow("Legendas", listOf(true to "Ligadas", false to "Desligadas"), subOn, vm::setSubOn, tonal = true)
        Spacer(Modifier.height(18.dp))
        PrefRow("Qualidade máxima", listOf(0 to "Sem limite", 2160 to "4K", 1080 to "1080p", 720 to "720p"), maxQ, vm::setMaxQuality, tonal = true)
    }
}

@Composable
private fun FirstRunPlatformOption(
    selected: Boolean,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    SettingsRow(
        icon = icon,
        title = title,
        sub = subtitle,
        iconTint = if (selected) Accent else TextSecondary,
        modifier = if (selected) Modifier.border(1.dp, Accent.copy(alpha = 0.6f), RoundedCornerShape(14.dp)) else Modifier,
        trailing = if (selected) ({ Icon(Icons.Default.CheckCircle, "Selecionado", tint = Accent) }) else null,
        onClick = onClick,
    )
}
