package com.joabtv.app.ui.settings

import androidx.compose.foundation.background
import com.joabtv.app.ui.common.topInsetsPadding
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.joabtv.app.data.local.MAX_PROFILES
import com.joabtv.app.data.local.PROFILE_COLORS
import com.joabtv.app.data.local.PlatformPrefs
import com.joabtv.app.data.local.Profile
import com.joabtv.app.data.local.ProfilePhotos
import com.joabtv.app.data.local.CopyMode
import com.joabtv.app.data.local.ProfileCategory
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.joabtv.app.data.repository.LibraryRepository
import com.joabtv.app.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val prefs: PlatformPrefs,
    private val library: LibraryRepository,
    private val hub: com.joabtv.app.data.accounts.AccountsHub,
    private val nuvio: com.joabtv.app.data.accounts.NuvioAccountService,
) : ViewModel() {
    val profiles = prefs.profiles
    val profileId = prefs.profileId

    /** A aba Addons aparece no menu da TV (por perfil)? */
    val menuAddons = prefs.menuAddonsFlow

    /** A conta Nuvio está conectada (a escolha de foto do catálogo só aparece então). */
    val nuvioConnected = hub.nuvioState.map { it.signedIn }
        .stateIn(viewModelScope, SharingStarted.Eagerly, hub.nuvioState.value.signedIn)

    fun select(id: String) = prefs.selectProfile(id)

    /** Cria o perfil. Com [copyFrom] ele nasce com uma cópia das [cats] daquele perfil. Devolve o criado, ou null no limite. */
    fun add(name: String, kids: Boolean, color: Int, avatar: String = "", copyFrom: String? = null, cats: Set<ProfileCategory> = emptySet()): Profile? {
        val p = prefs.addProfile(name, kids) ?: return null
        prefs.updateProfile(p.copy(color = color, avatar = ProfilePhotos.adopt(context, p.id, avatar)))
        if (copyFrom != null && cats.isNotEmpty()) prefs.copyProfileData(copyFrom, p.id, cats, CopyMode.REPLACE)
        return p
    }

    // ── Fontes e catálogo (addons, plugins, catálogo, TV ao vivo e debrid de cada perfil) ──

    /** Nome do perfil de quem [profileId] usa os dados em [cat]. null quando são do próprio perfil. */
    fun ownerName(profileId: String, cat: ProfileCategory): String? {
        val owner = prefs.ownerOf(profileId, cat)
        return if (owner == profileId) null else prefs.profiles.value.firstOrNull { it.id == owner }?.name ?: "outro perfil"
    }

    /** Nomes dos perfis que usam algum dado de [id]. Eles ficam com uma cópia quando [id] é removido. */
    fun usedBy(id: String): List<String> = prefs.profiles.value
        .filter { p -> p.id != id && p.links.keys.any { k -> ProfileCategory.of(k)?.let { prefs.ownerOf(p.id, it) } == id } }
        .map { it.name }

    fun copy(from: String, to: String, cats: Set<ProfileCategory>, mode: CopyMode) = prefs.copyProfileData(from, to, cats, mode)

    /** Liga [profileId] aos dados de [from]. Devolve quantas categorias não deu para ligar (criariam um ciclo). */
    fun link(profileId: String, cats: Set<ProfileCategory>, from: String): Int = cats.count { !prefs.linkProfile(profileId, it, from) }

    fun unlink(profileId: String, cat: ProfileCategory) = prefs.unlinkProfile(profileId, cat)
    fun update(p: Profile) {
        // Trocou a foto de arquivo por outra coisa, então o arquivo velho não serve mais.
        if (!ProfilePhotos.isFile(p.avatar)) ProfilePhotos.delete(context, p.id)
        prefs.updateProfile(p)
    }

    /** Foto escolhida na galeria, reduzida e guardada no app. [profileId] vazio é de perfil novo. null se não leu a imagem. */
    suspend fun savePhoto(uri: android.net.Uri, profileId: String): String? =
        ProfilePhotos.savePicked(context, uri, profileId.ifBlank { ProfilePhotos.NEW_PROFILE })

    /** A foto do perfil do Nuvio escolhido na importação (se tiver) e depois o catálogo de fotos do Nuvio. */
    suspend fun nuvioPhotos(): Result<List<com.joabtv.app.data.accounts.NuvioAvatar>> {
        val catalog = nuvio.avatarCatalog().getOrElse { return Result.failure(it) }
        val pick = hub.nuvioPick().index
        val own = nuvio.profiles().getOrNull()?.firstOrNull { it.index == pick }?.avatarUrl.orEmpty()
        return Result.success(
            if (own.isBlank() || catalog.any { it.url == own }) catalog
            else listOf(com.joabtv.app.data.accounts.NuvioAvatar("own", "Foto do seu perfil", "", own)) + catalog,
        )
    }
    fun remove(id: String) {
        if (prefs.removeProfile(id)) viewModelScope.launch { library.dropProfile(id) }
    }

    // PIN dos pais: trava sair do infantil e entrar em editar perfis com infantil ativo.
    fun hasPin() = prefs.hasParentalPin
    fun checkPin(pin: String) = prefs.checkParentalPin(pin)
    fun setPin(pin: String) = prefs.setParentalPin(pin)
}

/** Círculo com a inicial na cor do perfil. Usado na grade, na barra e nas Configurações. */
@Composable
fun ProfileAvatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val initialSize = with(density) { (size * 0.44f).toSp() }
    val kidsSize = with(density) { (size * 0.13f).coerceAtLeast(7.dp).toSp() }
    val avatarText = androidx.compose.ui.text.TextStyle(
        fontFamily = com.joabtv.app.ui.theme.Inter,
        platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
        lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
            alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
            trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
        ),
    )
    // Foto do perfil (do Nuvio, do Stremio ou da galeria). Enquanto não chega, ou se falhar, a inicial segue valendo.
    val context = androidx.compose.ui.platform.LocalContext.current
    val photo = remember(profile.avatar) { ProfilePhotos.model(context, profile.avatar) }
    var photoShown by remember(photo) { mutableStateOf(false) }
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f))
            .background(Color(PROFILE_COLORS[profile.color.mod(PROFILE_COLORS.size)])),
        contentAlignment = Alignment.Center,
    ) {
        if (!photoShown) Text(
            profile.name.trim().take(1).uppercase().ifBlank { "?" },
            color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = initialSize,
            lineHeight = initialSize, textAlign = TextAlign.Center, style = avatarText,
        )
        if (photo != null) coil.compose.AsyncImage(
            model = photo, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
            onSuccess = { photoShown = true }, onError = { photoShown = false },
        )
        if (profile.kids && size >= 48.dp) {
            Text(
                "KIDS", color = Color.White, fontSize = kidsSize, lineHeight = kidsSize,
                fontWeight = FontWeight.Black, textAlign = TextAlign.Center, style = avatarText,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = size * 0.06f)
                    // Sobre foto o texto branco precisa de fundo para ler.
                    .then(if (photoShown) Modifier.background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 3.dp) else Modifier),
            )
        }
    }
}

/**
 * "Quem está assistindo?". Abre sozinha quando há mais de um perfil e também pela barra e pelas
 * Configurações. Em modo edição, tocar num perfil abre o painel para renomear, trocar a cor,
 * marcar infantil ou remover.
 */
@Composable
fun ProfilesScreen(
    onPicked: () -> Unit,
    startEditing: Boolean = false,
    vm: ProfilesViewModel = hiltViewModel(),
) {
    val profiles by vm.profiles.collectAsStateWithLifecycle()
    val current by vm.profileId.collectAsStateWithLifecycle()
    val me = profiles.firstOrNull { it.id == current }
    var editing by remember { mutableStateOf(startEditing) }
    // null fechado, Profile com id vazio é "novo perfil".
    var panel by remember { mutableStateOf<Profile?>(null) }
    // PIN dos pais: perfil pra trocar depois de confirmar, ou "entrar em editar" pendente.
    var pendingSwitch by remember { mutableStateOf<Profile?>(null) }
    var pendingEdit by remember { mutableStateOf(false) }
    var showSetPinNudge by remember { mutableStateOf(false) }
    // Perfil novo esperando o Como começar, painel Fontes e catálogo (id do perfil) e as escolhas que abrem dele.
    var startFor by remember { mutableStateOf<Profile?>(null) }
    var sources by remember { mutableStateOf<String?>(null) }
    var sourcePick by remember { mutableStateOf<SourceAsk?>(null) }
    var catAsk by remember { mutableStateOf<CatAsk?>(null) }
    var unlinkAsk by remember { mutableStateOf<ProfileCategory?>(null) }
    // Controle remoto começa no perfil em uso.
    val currentTile = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(200); runCatching { currentTile.requestFocus() } }

    Column(
        Modifier.fillMaxSize()
            .background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color(0xFF17212E), Background), radius = 1400f))
            .verticalScroll(rememberScrollState())
            .topInsetsPadding().padding(horizontal = 16.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            if (editing) "Editar perfis" else "Quem está assistindo?",
            color = TextPrimary, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center, letterSpacing = (-0.6).sp,
        )
        Text(
            if (editing) "${if (com.joabtv.app.ui.common.LocalIsTv.current) "Escolha um" else "Toque num"} perfil para mudar nome, foto, cor, modo infantil ou fontes"
            else "Cada perfil tem a própria Minha Lista, o próprio histórico, addons e plugins",
            color = TextSecondary, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(36.dp))
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            profiles.forEach { p ->
                ProfileTile(
                    label = p.name,
                    modifier = if (p.id == current) Modifier.focusRequester(currentTile) else Modifier,
                    selected = p.id == current && !editing,
                    onClick = {
                        if (editing) panel = p
                        // Sair do infantil pra outro perfil pede o PIN, quando existe um definido.
                        else if (me?.kids == true && !p.kids && vm.hasPin()) pendingSwitch = p
                        else { vm.select(p.id); onPicked() }
                    },
                ) {
                    Box {
                        ProfileAvatar(p, 96.dp)
                        if (editing) {
                            Box(
                                Modifier.matchParentSize().clip(RoundedCornerShape(27.dp)).background(Color(0x88000000)),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Default.Edit, "Editar ${p.name}", tint = Color.White, modifier = Modifier.size(30.dp)) }
                        }
                    }
                }
            }
            if (profiles.size < MAX_PROFILES) {
                ProfileTile(label = "Adicionar", selected = false, onClick = {
                    panel = Profile("", "", profiles.size % PROFILE_COLORS.size, false)
                }) {
                    Box(
                        Modifier.size(96.dp).clip(RoundedCornerShape(27.dp)).border(2.dp, Border, RoundedCornerShape(27.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Default.Add, "Adicionar perfil", tint = TextSecondary, modifier = Modifier.size(40.dp)) }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
        com.joabtv.app.ui.common.JoabButton(
            if (editing) "Pronto" else "Editar perfis",
            {
                // Entrar em editar com o infantil ativo pede o PIN. Sair (Pronto) não pede nada.
                if (!editing && me?.kids == true && vm.hasPin()) pendingEdit = true else editing = !editing
            },
            icon = if (editing) Icons.Default.Check else Icons.Default.Edit,
            tone = if (editing) com.joabtv.app.ui.common.ButtonTone.PRIMARY else com.joabtv.app.ui.common.ButtonTone.SECONDARY,
        )
    }

    val ctx = androidx.compose.ui.platform.LocalContext.current
    fun toast(text: String) = android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()

    panel?.let { p ->
        ProfileEditDialog(
            initial = p,
            canRemove = p.id.isNotEmpty() && profiles.size > 1,
            usedBy = if (p.id.isEmpty()) emptyList() else vm.usedBy(p.id),
            onDismiss = { panel = null },
            onSave = { edited ->
                val becameKids = edited.kids && !p.kids
                panel = null
                // Perfil novo ainda pergunta como começar (do zero ou copiando de outro). Só nasce lá.
                if (edited.id.isEmpty()) startFor = edited else {
                    vm.update(edited)
                    // Marcou infantil pela 1ª vez sem PIN nenhum definido no aparelho. Convida a criar um agora,
                    // senão o modo infantil não trava nada (qualquer um sai dele na hora).
                    if (becameKids && !vm.hasPin()) showSetPinNudge = true
                }
            },
            onRemove = { vm.remove(p.id); panel = null },
            onSources = if (p.id.isEmpty()) null else ({ sources = p.id }),
        )
    }

    // Criar perfil. Do zero vem primeiro, depois uma linha Copiar de para cada perfil.
    startFor?.let { draft ->
        fun create(from: Profile?, cats: Set<ProfileCategory>) {
            startFor = null
            catAsk = null
            if (vm.add(draft.name, draft.kids, draft.color, draft.avatar, from?.id, cats) == null) { toast("Já existem $MAX_PROFILES perfis"); return }
            if (from != null) toast("Perfil ${draft.name.trim()} criado com o que vem de ${from.name}")
            if (draft.kids && !vm.hasPin()) showSetPinNudge = true
        }
        if (catAsk == null) StartPanel(
            name = draft.name.trim(), profiles = profiles,
            onZero = { create(null, emptySet()) },
            onCopy = { src ->
                catAsk = CatAsk(
                    title = "Copiar de ${src.name}", sub = "Para ${draft.name.trim()}", header = "O que copiar", go = "Criar perfil",
                    kids = draft.kids, modes = false, onGo = { cats, _ -> create(src, cats) },
                )
            },
            onDismiss = { startFor = null },
        )
    }

    // Fontes e catálogo de um perfil que já existe. Lê o perfil de novo a cada mudança de vínculo.
    sources?.let { id ->
        val p = profiles.firstOrNull { it.id == id }
        if (p == null) { sources = null; return@let }
        val others = profiles.filter { it.id != id }
        if (catAsk == null && sourcePick == null) SourcesPanel(
            p, owners = ProfileCategory.entries.associateWith { vm.ownerName(p.id, it) },
            canBorrow = others.isNotEmpty(),
            onUnlink = { cat -> unlinkAsk = cat },
            onCopy = {
                sourcePick = SourceAsk("Copiar para ${p.name}") { from ->
                    catAsk = CatAsk(
                        title = "Copiar de ${from.name}", sub = "Para ${p.name}", header = "O que copiar", go = "Copiar",
                        kids = p.kids, modes = true,
                        onGo = { cats, mode -> vm.copy(from.id, p.id, cats, mode); catAsk = null; toast("Copiado de ${from.name}") },
                    )
                }
            },
            onUse = {
                sourcePick = SourceAsk("Usar o mesmo em ${p.name}") { from ->
                    catAsk = CatAsk(
                        title = "Usar o de ${from.name}",
                        sub = "Em ${p.name}. O que ${p.name} tem hoje fica guardado, mas só aparece o de ${from.name}.",
                        header = "O que usar", go = "Usar o mesmo", kids = p.kids, modes = false,
                        onGo = { cats, _ ->
                            val failed = vm.link(p.id, cats, from.id)
                            catAsk = null
                            toast(if (failed == 0) "${p.name} usa o mesmo de ${from.name}" else "Parte ficou como estava porque ${from.name} já usa o de ${p.name}")
                        },
                    )
                }
            },
            onDismiss = { sources = null },
        )
        unlinkAsk?.let { cat ->
            val owner = vm.ownerName(p.id, cat) ?: "outro perfil"
            CatalogConfirm(
                title = "Deixar de usar ${categoryLabel(cat)} de $owner?", body = "${p.name} fica com uma cópia e passa a mudar sozinho.",
                confirm = "Deixar de usar", onConfirm = { vm.unlink(p.id, cat); unlinkAsk = null }, onDismiss = { unlinkAsk = null },
            )
        }
        sourcePick?.let { ask ->
            LiveChoice(
                ask.title, "De qual perfil?", others.map { Triple(it.name, if (it.kids) "Perfil infantil" else null, false) },
                onPick = { i -> sourcePick = null; ask.onPick(others[i]) }, onDismiss = { sourcePick = null },
            )
        }
    }

    catAsk?.let { CategoryPanel(it, onDismiss = { catAsk = null }) }

    pendingSwitch?.let { target ->
        VerifyPinDialog(
            onConfirm = { pin ->
                if (vm.checkPin(pin)) { vm.select(target.id); pendingSwitch = null; onPicked(); true } else false
            },
            onDismiss = { pendingSwitch = null },
        )
    }
    if (pendingEdit) {
        VerifyPinDialog(
            onConfirm = { pin -> if (vm.checkPin(pin)) { editing = true; pendingEdit = false; true } else false },
            onDismiss = { pendingEdit = false },
        )
    }
    if (showSetPinNudge) {
        SetPinDialog(
            title = "Criar PIN pro infantil?",
            onSet = { pin -> vm.setPin(pin); showSetPinNudge = false },
            onDismiss = { showSetPinNudge = false },
        )
    }
}

/** Perfil da grade. Foco do controle aumenta o avatar com anel branco (.prof-tile.is-focus). */
@Composable
private fun ProfileTile(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, avatar: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val tileScale = androidx.compose.ui.platform.LocalDensity.current.fontScale.coerceIn(1f, 1.5f)
    Column(
        modifier.width(120.dp * tileScale).onFocusChanged { focused = it.isFocused }
            .clickable(remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, null, onClick = onClick)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.scale(if (focused) 1.08f else 1f)
                .border(3.dp, if (focused || selected) TextPrimary else Color.Transparent, RoundedCornerShape(30.dp)).padding(3.dp),
        ) { avatar() }
        Spacer(Modifier.height(10.dp))
        Text(
            label, color = if (selected || focused) TextPrimary else TextSecondary, fontSize = 16.sp,
            fontWeight = FontWeight.Bold, lineHeight = 22.sp, maxLines = 2,
            textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ProfileEditDialog(
    initial: Profile,
    canRemove: Boolean,
    usedBy: List<String>,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
    onRemove: () -> Unit,
    onSources: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial.name) }
    var kids by remember { mutableStateOf(initial.kids) }
    var color by remember { mutableIntStateOf(initial.color) }
    var avatar by remember { mutableStateOf(initial.avatar) }
    var confirmRemove by remember { mutableStateOf(false) }
    val draft = initial.copy(name = name, kids = kids, color = color, avatar = avatar)
    // Na TV o nome vem do teclado na tela. Perfil novo já abre pedindo o nome.
    val tv = com.joabtv.app.ui.common.LocalIsTv.current
    var naming by remember { mutableStateOf(tv && initial.id.isEmpty()) }
    // Uma janela por vez. O teclado some e a ficha do perfil volta com o nome novo.
    if (naming) {
        com.joabtv.app.ui.common.TvTextPrompt(
            title = if (initial.id.isEmpty()) "Novo perfil" else "Renomear perfil",
            initial = name,
            onDone = { name = it.take(20); naming = false },
            // Desistir do nome de um perfil novo desiste do perfil.
            onDismiss = { if (initial.id.isEmpty() && name.isBlank()) onDismiss() else naming = false },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundCard,
        title = { Text(if (initial.id.isEmpty()) "Novo perfil" else "Editar perfil", color = TextPrimary) },
        text = {
            // Rola quando a tela é baixa ou a fonte é grande, senão os botões de baixo ficam fora do alcance.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProfileAvatar(draft.copy(name = name.ifBlank { "?" }), 64.dp)
                    if (tv) com.joabtv.app.ui.common.SettingsRow(
                        icon = Icons.Default.Edit, title = name.ifBlank { "Sem nome" }, sub = "Renomear",
                        modifier = Modifier.weight(1f), onClick = { naming = true },
                    ) else OutlinedTextField(
                        value = name, onValueChange = { name = it.take(20) },
                        label = { Text("Nome") }, singleLine = true, modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent, unfocusedBorderColor = Border,
                            focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent,
                        ),
                    )
                }
                ProfilePhotoChooser(avatar = avatar, profileId = initial.id, onChange = { avatar = it })
                Text("Cor", color = TextTertiary, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PROFILE_COLORS.forEachIndexed { i, c ->
                        Box(
                            Modifier.size(36.dp).clip(CircleShape).background(Color(c))
                                .border(3.dp, if (i == color) Color.White else Color.Transparent, CircleShape)
                                .clickable { color = i },
                            contentAlignment = Alignment.Center,
                        ) { if (i == color) Icon(Icons.Default.Check, "Cor escolhida", tint = Color.White, modifier = Modifier.size(18.dp)) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { kids = !kids }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Perfil infantil", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        Text("Só desenhos e títulos até 12 anos", color = TextTertiary, fontSize = 12.sp)
                    }
                    // A linha inteira já liga e desliga. O controle não precisa parar no Switch.
                    Switch(checked = kids, onCheckedChange = { kids = it }, modifier = Modifier.focusProperties { canFocus = false })
                }
                if (onSources != null) com.joabtv.app.ui.common.SettingsRow(
                    icon = Icons.Default.Extension, title = "Fontes e catálogo", sub = "Addons, plugins, catálogo, TV ao vivo e debrid",
                    onClick = onSources,
                )
                if (canRemove) {
                    TextButton(onClick = { confirmRemove = true }) {
                        Text("Remover perfil", color = LiveRed, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }, enabled = name.isNotBlank()) { Text("Salvar", color = Accent, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) } },
    )
    if (confirmRemove) CatalogConfirm(
        title = "Remover ${initial.name}?", body = removeProfileBody(usedBy), confirm = "Remover", danger = true,
        onConfirm = { confirmRemove = false; onRemove() }, onDismiss = { confirmRemove = false },
    )
}

/** Pedido de escolha das categorias. [modes] liga a opção Substituir ou Somar e [go] é o rótulo de quem confirma. */
private class CatAsk(
    val title: String, val sub: String, val header: String, val go: String, val kids: Boolean, val modes: Boolean,
    val onGo: (Set<ProfileCategory>, CopyMode) -> Unit,
)

/** Pedido de escolha do perfil de onde vem o que será copiado ou usado. */
private class SourceAsk(val title: String, val onPick: (Profile) -> Unit)

private fun categoryIcon(c: ProfileCategory) = when (c) {
    ProfileCategory.ADDONS -> Icons.Default.Extension
    ProfileCategory.PLUGINS -> Icons.Default.Hub
    ProfileCategory.CATALOG -> Icons.Default.GridView
    ProfileCategory.LIVE -> Icons.Default.LiveTv
    ProfileCategory.DEBRID -> Icons.Default.Bolt
}

/** Rótulo de seção dos painéis, igual ao "Trazer só uma parte" do painel Mais. */
@Composable
private fun PanelHeader(text: String) {
    Text(
        text.uppercase(), color = Accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp, fontFamily = Inter,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

/** Como começar um perfil novo. Do zero ou copiando de outro perfil. */
@Composable
private fun StartPanel(name: String, profiles: List<Profile>, onZero: () -> Unit, onCopy: (Profile) -> Unit, onDismiss: () -> Unit) {
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LivePanel("Como começar $name", "Addons, plugins, catálogo, TV ao vivo e debrid", onDismiss) {
        LiveItem("Do zero", onZero, Modifier.focusRequester(first), icon = Icons.Default.Add, sub = "Sem addons nem plugins, você escolhe depois")
        profiles.forEach { src ->
            LiveItem("Copiar de ${src.name}", { onCopy(src) }, icon = Icons.Default.Person, sub = "Nasce igual e depois cada um muda sozinho")
        }
    }
    if (com.joabtv.app.ui.common.LocalIsTv.current) LaunchedEffect(Unit) { liveFocusSoon(first) }
}

/**
 * Escolha do que vai de um perfil para outro. Vêm marcados addons, plugins, catálogo e TV ao vivo, e o debrid vem fora
 * porque leva as chaves. Perfil infantil não leva plugins.
 */
@Composable
private fun CategoryPanel(ask: CatAsk, onDismiss: () -> Unit) {
    val cats = remember(ask) { copyCategories(ask.kids) }
    var sel by remember(ask) { mutableStateOf(defaultCopySelection(ask.kids)) }
    var mode by remember(ask) { mutableStateOf(CopyMode.REPLACE) }
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LivePanel(ask.title, ask.sub, onDismiss) {
        PanelHeader(ask.header)
        cats.forEachIndexed { i, c ->
            val on = c in sel
            LiveItem(
                categoryLabel(c), { sel = if (on) sel - c else sel + c }, if (i == 0) Modifier.focusRequester(first) else Modifier,
                icon = categoryIcon(c), sub = if (c == ProfileCategory.DEBRID) "Leva as chaves de debrid" else null,
                tag = if (on) "Vai" else "Fica de fora", selected = on,
            )
        }
        if (ask.modes) LiveItem(
            "O que o perfil já tem", { mode = if (mode == CopyMode.MERGE) CopyMode.REPLACE else CopyMode.MERGE },
            icon = Icons.Default.SwapHoriz, tag = if (mode == CopyMode.MERGE) "Somar" else "Substituir",
            sub = if (mode == CopyMode.MERGE) "Soma o que faltar e mantém o que ele já tem" else "Troca pelo que vem do outro perfil",
        )
        val none = sel.isEmpty()
        LiveItem(if (none) "Marque o que trazer" else ask.go, { if (!none) ask.onGo(sel, mode) }, icon = Icons.Default.Check)
    }
    if (com.joabtv.app.ui.common.LocalIsTv.current) LaunchedEffect(ask) { liveFocusSoon(first) }
}

/** Fontes e catálogo do perfil. Mostra o que é próprio e o que usa o de outro perfil, e leva a copiar ou usar o mesmo. */
@Composable
private fun SourcesPanel(
    p: Profile,
    owners: Map<ProfileCategory, String?>,
    canBorrow: Boolean,
    onUnlink: (ProfileCategory) -> Unit,
    onCopy: () -> Unit,
    onUse: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tv = com.joabtv.app.ui.common.LocalIsTv.current
    val first = remember { androidx.compose.ui.focus.FocusRequester() }
    LivePanel("Fontes e catálogo", p.name, onDismiss) {
        PanelHeader("Neste perfil")
        ProfileCategory.entries.forEachIndexed { i, c ->
            val owner = owners[c]
            LiveItem(
                categoryLabel(c), { if (owner != null) onUnlink(c) }, if (i == 0) Modifier.focusRequester(first) else Modifier,
                icon = categoryIcon(c), sub = categoryLine(owner, tv), tag = owner?.let { "Usa $it" },
            )
        }
        if (canBorrow) {
            PanelHeader("Trazer de outro perfil")
            LiveItem("Copiar de outro perfil", onCopy, icon = Icons.Default.Add, sub = "Nasce igual e depois cada um muda sozinho")
            LiveItem("Usar o mesmo de outro perfil", onUse, icon = Icons.Default.SwapHoriz, sub = "Os dois ficam sempre iguais")
        }
    }
    if (tv) LaunchedEffect(Unit) { liveFocusSoon(first) }
}
