package com.joabtv.app.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joabtv.app.data.remote.AddonLinks
import com.joabtv.app.ui.common.ButtonTone
import com.joabtv.app.ui.common.ContentWithSummary
import com.joabtv.app.ui.common.Eyebrow
import com.joabtv.app.ui.common.GlassCircleButton
import com.joabtv.app.ui.common.JoabButton
import com.joabtv.app.ui.common.LocalIsTv
import com.joabtv.app.ui.common.SummaryCard
import com.joabtv.app.ui.common.SummaryRow
import com.joabtv.app.ui.common.TvTextFieldGate
import com.joabtv.app.ui.common.joabTween
import com.joabtv.app.ui.common.pagePadding
import com.joabtv.app.ui.common.rememberReducedMotion
import com.joabtv.app.ui.common.topInsetsPadding
import com.joabtv.app.ui.theme.*
import kotlinx.coroutines.delay

private typealias HRow = HomeCustomizeViewModel.Row
private typealias HState = HomeCustomizeViewModel.UiState

// Colunas fixas dos botões da linha. Linha sem Mover deixa o espaço vazio, assim Baixo e Cima
// andam na mesma coluna.
private val W_MOVE = 132.dp
private val W_RENAME = 152.dp
private val W_MORE = 120.dp

/**
 * Minha Início. A linha inteira mostra ou esconde a fileira (OK na TV, toque no celular). Direita
 * anda em Mover, Renomear e Mais. O destaque abre em lista, Sugestões do JOTV liga e desliga o grupo
 * todo e Voltar ao padrão desfaz tudo com Desfazer. Aplica na hora.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HomeCustomizeScreen(
    onBack: () -> Unit,
    onOpenAddons: () -> Unit,
    onOpenStore: () -> Unit = {},
    onOpenCollections: () -> Unit = {},
    onOpenFolder: (String, String) -> Unit = { _, _ -> },
    onOpenAccounts: () -> Unit = {},
    viewModel: HomeCustomizeViewModel = hiltViewModel(),
) {
    val st by viewModel.state.collectAsStateWithLifecycle()
    val tv = LocalIsTv.current
    val pad = pagePadding()
    val reduced = rememberReducedMotion()
    val toast = rememberCatalogToast()
    val reqs = remember { HashMap<String, FocusRequester>() }
    fun req(k: String) = reqs.getOrPut(k) { FocusRequester() }
    var pending by remember { mutableStateOf<String?>(null) }

    var heroOpen by remember { mutableStateOf(false) }
    var moreKey by remember { mutableStateOf<String?>(null) }
    var rowsFor by remember { mutableStateOf<Pair<String, String>?>(null) }
    var rowsBack by remember { mutableStateOf<String?>(null) }
    var renamingKey by remember { mutableStateOf<String?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmSug by remember { mutableStateOf(false) }

    // Pede o foco a um alvo pelo nome. Tenta de novo até alguém na tela ficar com o foco de verdade.
    var hasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(pending) {
        val k = pending ?: return@LaunchedEffect
        if (tv) {
            delay(90)
            repeat(18) {
                val r: Any? = runCatching { req(k).requestFocus() }.getOrNull()
                delay(110)
                if (r != null && r != false && hasFocus) { pending = null; return@LaunchedEffect }
                delay(40)
            }
        }
        pending = null
    }

    // Foco inicial. O aviso de addon grande, senão a primeira fileira nova (quando vem da loja), senão a primeira.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(st.rows.isNotEmpty(), st.kids) {
        if (started || st.rows.isEmpty() || st.kids) return@LaunchedEffect
        started = true
        pending = when {
            st.big != null -> "warn-keep"
            st.focusNew -> st.main.firstOrNull { it.isNew }?.let { it.key + "#line" }
            else -> st.main.firstOrNull()?.let { it.key + "#line" }
        }
    }

    // Voltar fecha, na ordem, o modo Mover, o grupo Escondidas e por fim a tela.
    BackHandler(enabled = st.groupOpen) { viewModel.closeGroup(); pending = "group" }
    BackHandler(enabled = st.moving != null) {
        val k = st.moving
        viewModel.cancelMove()
        if (k != null) pending = "$k#move"
    }

    fun tap(row: HRow) {
        when (val t = viewModel.tap(row)) {
            is HomeCustomizeViewModel.Tap.OpenAddons -> onOpenAddons()
            HomeCustomizeViewModel.Tap.SuggestionsOn -> toast.show("Sugestões do JOTV ligadas")
            is HomeCustomizeViewModel.Tap.Toggled -> {
                toast.show(t.title + if (t.on) " voltou para a Início" else " saiu da Início", 5000L) {
                    viewModel.setEnabled(t.key, !t.on); pending = t.key + "#line"
                }
                // A linha pode mudar de lugar (entra no grupo ou sai dele). O foco vai junto.
                pending = t.key + "#line"
            }
        }
    }

    fun toggleSuggestions() {
        when {
            !st.catalogOn -> { viewModel.setCatalogOn(true); toast.show("Sugestões do JOTV ligadas") }
            st.othersOn -> { viewModel.setCatalogOn(false); toast.show("Sugestões do JOTV desligadas") }
            else -> confirmSug = true
        }
    }

    fun openRows(url: String, name: String, back: String) { rowsFor = url to name; rowsBack = back }

    Box(
        Modifier.fillMaxSize().background(Background)
            .onFocusChanged { hasFocus = it.hasFocus }
            // Na TV o OK desfaz o aviso à mostra. Qualquer outra tecla solta o OK e segue.
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val ok = ev.key == Key.DirectionCenter || ev.key == Key.Enter || ev.key == Key.NumPadEnter
                if (ok && toast.armed && st.moving == null) return@onPreviewKeyEvent toast.undoNow()
                toast.armed = false
                false
            },
    ) {
        Column(Modifier.fillMaxSize().topInsetsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = pad, top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!tv) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = TextPrimary)
                }
                Column(Modifier.weight(1f).padding(start = if (tv) pad else 0.dp, top = 12.dp, bottom = 6.dp)) {
                    Eyebrow("Seu catálogo")
                    Text("Minha Início", color = TextPrimary, fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp)
                    val n = st.appearing.size
                    if (!st.kids && st.rows.isNotEmpty()) Text(
                        (if (n == 1) "1 fileira aparece" else "${catNum(n)} fileiras aparecem") + ". Vale para o perfil ${st.profile}.",
                        color = TextTertiary, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            if (st.kids) {
                com.joabtv.app.ui.common.JoabEmptyState(
                    Icons.Default.Lock, "Minha Início fica nos perfis de adulto",
                    "No perfil infantil a Início mostra só as sugestões do JOTV para a idade e o seu progresso.",
                )
                return@Column
            }

            val summary: @Composable () -> Unit = { SummaryPanel(st, tv) }
            ContentWithSummary(summary) { below ->
                val main = st.main
                val hidden = st.hidden
                val numbers = st.numbers
                val listState = rememberLazyListState()
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(start = pad, end = pad, top = 6.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "bar") {
                        TopBar(
                            st, tv, ::req,
                            onHero = { heroOpen = true },
                            onSuggestions = ::toggleSuggestions,
                            onReset = { confirmReset = true },
                        )
                    }
                    st.big?.let { big ->
                        item(key = "big") {
                            BigAddonBox(
                                big, tv, ::req,
                                onKeep = {
                                    viewModel.keepTen(big.base)
                                    toast.show("Ficaram ${com.joabtv.app.data.local.HomeLayout.AUTO_ROWS} fileiras do ${big.name}. As outras estão em Escondidas.", 4000L)
                                    pending = st.main.firstOrNull()?.let { it.key + "#line" }
                                },
                                onPick = {
                                    st.addonBoxes.firstOrNull { AddonLinks.baseOf(it.url) == big.base }?.let { openRows(it.url, it.name, "warn-pick") }
                                },
                                onAll = { viewModel.keepAll(big.base); pending = st.main.firstOrNull()?.let { it.key + "#line" } },
                            )
                        }
                    }
                    item(key = "addons") {
                        if (st.addonBoxes.isEmpty()) InviteBox(st, tv, ::req, onStore = onOpenStore, onAccounts = onOpenAccounts)
                        else AddonsBox(st, tv, ::req, onOpen = { openRows(it.url, it.name, "addon#" + it.url) })
                    }
                    items(main, key = { it.key }) { r ->
                        RowItem(
                            r, numbers[r.key], st.moving == r.key, tv, ::req,
                            Modifier.animateItem(placementSpec = if (reduced) null else spring<IntOffset>(stiffness = 400f)),
                            RowActions(
                                tap = { tap(r) },
                                startMove = { viewModel.startMove(r.key) },
                                step = viewModel::moveStep,
                                edge = viewModel::moveEdge,
                                commit = {
                                    val n = viewModel.commitMove()
                                    pending = r.key + "#move"
                                    if (n != null) toast.show("${r.title} agora é a ${n}ª fileira", 3000L)
                                },
                                rename = { renamingKey = r.key },
                                more = { moreKey = r.key },
                                drop = {
                                    val next = main.getOrNull(main.indexOf(r) + 1) ?: main.getOrNull(main.indexOf(r) - 1)
                                    viewModel.drop(r.key)
                                    toast.show("${r.title} saiu da lista", 3000L)
                                    pending = next?.let { it.key + "#line" }
                                },
                                top = { viewModel.moveNow(r.key, true); toast.show("${r.title} foi para o topo", 3000L) },
                                bottom = { viewModel.moveNow(r.key, false); toast.show("${r.title} foi para o fim", 3000L) },
                                pin = { pin -> viewModel.setPinned(r.collectionId.orEmpty(), pin) },
                                hero = { viewModel.setHero(r.key); toast.show("Destaque ${r.title}", 3000L) },
                                rows = { openRows(r.addonUrl, r.addonName, r.key + "#more") },
                                folders = { r.collectionId?.let { c -> r.firstFolder?.let { f -> onOpenFolder(c, f) } } },
                                collections = onOpenCollections,
                            ),
                            st.heroSource,
                        )
                    }
                    if (hidden.isNotEmpty()) {
                        item(key = "group") {
                            PanelItem(
                                "Escondidas (${catNum(hidden.size)})",
                                onClick = {
                                    if (st.groupOpen) { viewModel.closeGroup(); pending = "group" }
                                    else {
                                        viewModel.openGroup()
                                        pending = hidden.firstOrNull()?.let { it.key + "#line" }
                                    }
                                },
                                modifier = Modifier.focusRequester(req("group")),
                                icon = if (st.groupOpen) Icons.Default.ExpandMore else Icons.Default.ChevronRight,
                            )
                        }
                        if (st.groupOpen) {
                            items(hidden.take(st.groupLimit), key = { it.key }) { r ->
                                RowItem(
                                    r, null, false, tv, ::req, Modifier,
                                    RowActions(
                                        tap = { tap(r) }, startMove = {}, step = {}, edge = {}, commit = {},
                                        rename = { renamingKey = r.key }, more = { moreKey = r.key },
                                        drop = {
                                            val shown = hidden.take(st.groupLimit)
                                            val next = shown.getOrNull(shown.indexOf(r) + 1) ?: shown.getOrNull(shown.indexOf(r) - 1)
                                            viewModel.drop(r.key)
                                            toast.show("${r.title} saiu da lista", 3000L)
                                            pending = next?.let { it.key + "#line" }
                                        },
                                        top = { viewModel.moveNow(r.key, true) }, bottom = { viewModel.moveNow(r.key, false) },
                                        pin = {}, hero = {}, rows = { openRows(r.addonUrl, r.addonName, r.key + "#more") },
                                        folders = {}, collections = onOpenCollections,
                                    ),
                                    st.heroSource,
                                )
                            }
                            if (hidden.size > st.groupLimit) item(key = "group-more") {
                                PanelItem(
                                    "Mostrar mais ${minOf(HomeCustomizeViewModel.PAGE, hidden.size - st.groupLimit)}",
                                    onClick = {
                                        val at = st.groupLimit
                                        viewModel.showMore()
                                        pending = hidden.getOrNull(at)?.let { it.key + "#line" }
                                    },
                                    icon = Icons.Default.Add,
                                )
                            }
                        }
                    }
                    below?.let { b -> item(key = "summary") { Spacer(Modifier.height(8.dp)); b() } }
                }
            }
        }

        CatalogToastHost(
            toast,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 18.dp, start = 12.dp, end = 12.dp),
        )
    }

    // ── Camadas ──────────────────────────────────────────────────────────────
    if (heroOpen) HeroPanel(
        st,
        onPick = { o ->
            viewModel.setHero(o.key)
            toast.show("Destaque ${o.label}", 3000L)
            heroOpen = false
            pending = "top#hero"
        },
        onDismiss = { heroOpen = false; pending = "top#hero" },
    )
    moreKey?.let { k ->
        val row = st.rows.firstOrNull { it.key == k }
        if (row != null) MorePanel(
            row, st, onDismiss = { moreKey = null; pending = "$k#more" },
            onHero = { viewModel.setHero(k); toast.show("Destaque ${row.title}", 3000L); moreKey = null; pending = "$k#more" },
            onRows = { moreKey = null; openRows(row.addonUrl, row.addonName, "$k#more") },
            onFolders = { moreKey = null; row.collectionId?.let { c -> row.firstFolder?.let { f -> onOpenFolder(c, f) } } },
            onPin = {
                row.collectionId?.let { viewModel.setPinned(it, !row.pinToTop) }
                toast.show(if (row.pinToTop) "${row.title} saiu do topo" else "${row.title} fixada no topo", 3000L)
                moreKey = null; pending = "$k#more"
            },
            onCollections = { moreKey = null; onOpenCollections() },
        )
    }
    rowsFor?.let { (url, name) ->
        AddonRowsPanel(url, name, onDismiss = { rowsFor = null; pending = rowsBack })
    }
    renamingKey?.let { k ->
        val row = st.rows.firstOrNull { it.key == k }
        if (row != null) RenameDialog(
            row,
            onSave = { text ->
                viewModel.rename(k, if (text.trim() == row.original) "" else text)
                renamingKey = null; pending = "$k#rename"
            },
            onDismiss = { renamingKey = null; pending = "$k#rename" },
        )
    }
    if (confirmReset) CatalogConfirm(
        title = "Voltar a Início ao padrão?",
        body = "Volta a ordem original, religa as sugestões do JOTV e o seu progresso, tira os nomes trocados e o destaque escolhido. Addons e coleções continuam como estão.",
        confirm = "Voltar ao padrão",
        onConfirm = {
            confirmReset = false
            val snap = viewModel.reset()
            toast.show("Início de volta ao padrão", 8000L) { viewModel.undoReset(snap) }
            pending = "top#reset"
        },
        onDismiss = { confirmReset = false; pending = "top#reset" },
    )
    if (confirmSug) CatalogConfirm(
        title = "Desligar mesmo?",
        body = "Sem addons, a Início fica só com o seu progresso.",
        confirm = "Desligar",
        onConfirm = { confirmSug = false; viewModel.setCatalogOn(false); toast.show("Sugestões do JOTV desligadas"); pending = "top#sug" },
        onDismiss = { confirmSug = false; pending = "top#sug" },
    )
}

/** O que cada botão da linha faz. Agrupado para a linha não carregar uma dúzia de parâmetros. */
private class RowActions(
    val tap: () -> Unit,
    val startMove: () -> Unit,
    val step: (Int) -> Unit,
    val edge: (Boolean) -> Unit,
    val commit: () -> Unit,
    val rename: () -> Unit,
    val more: () -> Unit,
    val drop: () -> Unit,
    val top: () -> Unit,
    val bottom: () -> Unit,
    val pin: (Boolean) -> Unit,
    val hero: () -> Unit,
    val rows: () -> Unit,
    val folders: () -> Unit,
    val collections: () -> Unit,
)

// ── Barra do topo ────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    st: HState,
    tv: Boolean,
    req: (String) -> FocusRequester,
    onHero: () -> Unit,
    onSuggestions: () -> Unit,
    onReset: () -> Unit,
) {
    var sugFocused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JoabButton("Destaque ${st.heroLabel}", onHero, Modifier.focusRequester(req("top#hero")), icon = Icons.Default.Movie, compact = true)
            PillButton(
                onSuggestions, Modifier.focusRequester(req("top#sug")),
                onFocus = { sugFocused = it },
                description = "Sugestões do JOTV ${if (st.catalogOn) "ligadas" else "desligadas"}",
            ) { fg ->
                Icon(Icons.Default.Layers, null, tint = fg, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Sugestões do JOTV", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(18.dp).background(Color.White.copy(alpha = .2f)))
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (st.catalogOn) Ok else TextTertiary))
                Text(if (st.catalogOn) "Ligadas" else "Desligadas", color = if (st.catalogOn) Ok else TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp), maxLines = 1)
            }
            JoabButton("Voltar ao padrão", onReset, Modifier.focusRequester(req("top#reset")), icon = Icons.Default.RestartAlt, compact = true)
        }
        // Na TV o apoio aparece com o foco no botão. No toque fica sempre à mostra.
        Text(
            "Em alta, cinemas, lançamentos, trailers e gêneros. Desligado, a Início fica com seu progresso, addons e coleções.",
            color = if (!tv || sugFocused) TextTertiary else Color.Transparent,
            fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** Botão pílula com o conteúdo livre (o JoabButton só aceita texto e ícone). Mesmo foco do JoabButton. */
@Composable
private fun PillButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFocus: (Boolean) -> Unit = {},
    description: String? = null,
    dim: Boolean = false,
    content: @Composable RowScope.(Color) -> Unit,
) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val reduced = rememberReducedMotion()
    val s = androidx.compose.animation.core.animateFloatAsState(
        if (reduced) 1f else if (focused) 1.05f else if (pressed) 0.97f else 1f, joabTween(160, reduced), label = "pill",
    ).value
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier
            .scale(s)
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(Surface2)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .clickable(interactionSource = src, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .alpha(if (dim) .6f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(Color.White) }
}

// ── Aviso de addon grande ────────────────────────────────────────────────────

@Composable
private fun BigAddonBox(
    big: com.joabtv.app.data.local.BigAddon,
    tv: Boolean,
    req: (String) -> FocusRequester,
    onKeep: () -> Unit,
    onPick: () -> Unit,
    onAll: () -> Unit,
) {
    val keep = com.joabtv.app.data.local.HomeLayout.AUTO_ROWS
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BackgroundCard2)
            .border(1.dp, Accent.copy(alpha = .45f), RoundedCornerShape(16.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("${big.name} trouxe ${catNum(big.n)} fileiras. Deixar só as $keep primeiras?", color = TextPrimary, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.ExtraBold)
        Text("As outras ficam em Escondidas e voltam quando você quiser. A pergunta aparece uma vez.", color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JoabButton("Deixar $keep", onKeep, Modifier.focusRequester(req("warn-keep")), icon = Icons.Default.Check, tone = ButtonTone.PRIMARY, compact = true)
            JoabButton("Escolher", onPick, Modifier.focusRequester(req("warn-pick")), icon = Icons.Default.GridView, compact = true)
            JoabButton("Manter todas", onAll, Modifier.focusRequester(req("warn-all")), icon = Icons.Default.Close, compact = true)
        }
    }
}

// ── Fileiras dos addons ──────────────────────────────────────────────────────

/**
 * Um botão por addon, no formato "FenixFlix, 2 de 4 na Início". Abre o mesmo painel da tela Addons
 * sem sair daqui e volta ao mesmo ponto.
 */
@Composable
private fun AddonsBox(st: HState, tv: Boolean, req: (String) -> FocusRequester, onOpen: (HomeCustomizeViewModel.AddonBox) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BackgroundCard2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Fileiras dos addons", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text("Escolha o que cada addon mostra na sua Início.", color = TextSecondary, fontSize = 14.sp)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            st.addonBoxes.forEach { a ->
                PillButton(
                    { onOpen(a) }, Modifier.focusRequester(req("addon#" + a.url)), dim = !a.enabled,
                    description = "${a.name}, ${a.on} de ${a.all} na Início" + if (a.enabled) "" else ", addon desligado",
                ) { fg ->
                    Icon(Icons.Default.Extension, null, tint = fg, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(a.name, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                    Box(Modifier.padding(horizontal = 10.dp).width(1.dp).height(18.dp).background(Color.White.copy(alpha = .2f)))
                    Text("${a.on} de ${a.all} na Início", color = TextSecondary, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Sem addon com fileiras, a seção vira o convite. */
@Composable
private fun InviteBox(st: HState, tv: Boolean, req: (String) -> FocusRequester, onStore: () -> Unit, onAccounts: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BackgroundCard2).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Quer mais fileiras?", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        Text("Addons como Netflix ou Anime e as suas coleções viram fileiras aqui.", color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp)
        @OptIn(ExperimentalLayoutApi::class)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            JoabButton("Instalar addons com fileiras", onStore, Modifier.focusRequester(req("invite-store")), icon = Icons.Default.TravelExplore, tone = ButtonTone.PRIMARY, compact = true)
            when (st.nuvio) {
                HomeCustomizeViewModel.NuvioState.OUT -> JoabButton("Entrar no Nuvio", onAccounts, Modifier.focusRequester(req("invite-nuvio")), icon = Icons.Default.AccountCircle, compact = true)
                HomeCustomizeViewModel.NuvioState.IN -> JoabButton("Trazer do Nuvio", onAccounts, Modifier.focusRequester(req("invite-nuvio")), icon = Icons.Default.Add, compact = true)
                HomeCustomizeViewModel.NuvioState.DONE -> Unit
            }
        }
    }
}

// ── Linha ────────────────────────────────────────────────────────────────────

private fun rowMeta(r: HRow, tv: Boolean): String {
    val verb = when (r.hint) {
        HomeCustomizeViewModel.Hint.ENABLE_SUGGESTIONS -> if (tv) ", OK liga" else ", toque para ligar"
        HomeCustomizeViewModel.Hint.OPEN_ADDONS -> if (tv) ", OK abre os addons" else ", toque abre os addons"
        HomeCustomizeViewModel.Hint.NONE -> ""
    }
    return r.meta + verb + if (r.renamed) ". Nome original ${r.original}" else ""
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RowItem(
    r: HRow,
    number: Int?,
    moving: Boolean,
    tv: Boolean,
    req: (String) -> FocusRequester,
    modifier: Modifier,
    a: RowActions,
    heroKey: String,
) {
    val bring = remember { BringIntoViewRequester() }
    // Quem troca de lugar no modo Mover continua à vista, mesmo com a lista andando por baixo.
    if (moving) {
        val pos = number
        LaunchedEffect(pos, moving) { runCatching { bring.bringIntoView() } }
    }
    Column(
        modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bring)
            .onPreviewKeyEvent { ev ->
                if (!moving) return@onPreviewKeyEvent false
                val handled = ev.key == Key.DirectionUp || ev.key == Key.DirectionDown || ev.key == Key.DirectionLeft ||
                    ev.key == Key.DirectionRight || ev.key == Key.DirectionCenter || ev.key == Key.Enter
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent handled
                when (ev.key) {
                    Key.DirectionUp -> a.step(-1)
                    Key.DirectionDown -> a.step(1)
                    Key.DirectionLeft -> a.edge(true)
                    Key.DirectionRight -> a.edge(false)
                    else -> a.commit()
                }
                handled
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (tv) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RowLine(r, number, moving, tv, Modifier.weight(1f).focusRequester(req(r.key + "#line")), a.tap)
                if (r.orphan) {
                    Spacer(Modifier.width(W_MOVE))
                    Spacer(Modifier.width(W_RENAME))
                    Box(Modifier.width(W_MORE)) {
                        JoabButton("Tirar", a.drop, Modifier.fillMaxWidth().focusRequester(req(r.key + "#drop")), icon = Icons.Default.Delete, tone = ButtonTone.DANGER, compact = true)
                    }
                } else {
                    Box(Modifier.width(W_MOVE)) {
                        if (r.movable) JoabButton("Mover", a.startMove, Modifier.fillMaxWidth().focusRequester(req(r.key + "#move")), icon = Icons.Default.SwapVert, compact = true)
                    }
                    Box(Modifier.width(W_RENAME)) {
                        JoabButton("Renomear", a.rename, Modifier.fillMaxWidth().focusRequester(req(r.key + "#rename")), icon = Icons.Default.Edit, compact = true)
                    }
                    Box(Modifier.width(W_MORE)) {
                        if (r.hasMore) JoabButton("Mais", a.more, Modifier.fillMaxWidth().focusRequester(req(r.key + "#more")), icon = Icons.Default.MoreHoriz, compact = true)
                    }
                }
            }
            if (moving) Text(
                "Cima e Baixo movem. Esquerda leva ao topo, Direita ao fim. OK solta. Voltar desfaz.",
                color = Accent, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp),
            )
        } else {
            PhoneRow(r, number, a, heroKey)
        }
    }
}

/** A linha inteira, que é o interruptor. O estado vem em texto e em ícone, nunca só pela cor. */
@Composable
private fun RowLine(r: HRow, number: Int?, moving: Boolean, tv: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    val desc = r.title + (if (r.appear) ", na Início, posição ${number ?: 0}" else ", escondida") + ". " + rowMeta(r, tv)
    Row(
        modifier
            .heightIn(min = 64.dp).clip(shape)
            .background(if (focused || moving) AccentSoft else BackgroundCard2)
            .then(if (moving) Modifier.border(2.dp, Accent, shape) else if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .semantics(mergeDescendants = true) {
                contentDescription = desc
                stateDescription = if (r.appear) "Na Início" else "Escondida"
            }
            .catRowClick(Role.Switch, enabled = !moving, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StateCell(r.appear, number)
        Column(Modifier.weight(1f).alpha(if (r.appear) 1f else .66f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.title.ifBlank { "Catálogo" }, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.isNew) Text(
                    "Nova", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Accent.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(rowMeta(r, tv), color = TextSecondary, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (moving) {
            Icon(Icons.Default.ArrowUpward, null, tint = Accent, modifier = Modifier.size(22.dp))
            Icon(Icons.Default.ArrowDownward, null, tint = Accent, modifier = Modifier.size(22.dp))
        }
    }
}

/** Visto e número quando a fileira aparece. Anel cinza e "escondida" quando não aparece. */
@Composable
private fun StateCell(appear: Boolean, number: Int?) {
    Row(Modifier.width(96.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (appear) {
            Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(22.dp))
            Text("${number ?: ""}", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        } else {
            Icon(Icons.Default.RadioButtonUnchecked, null, tint = TextTertiary, modifier = Modifier.size(22.dp))
            Text("escondida", color = TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** Linha do celular. Toque na linha mostra ou esconde, o interruptor repete o estado e o menu traz o resto. */
@Composable
private fun PhoneRow(r: HRow, number: Int?, a: RowActions, heroKey: String) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val desc = r.title + (if (r.appear) ", na Início, posição ${number ?: 0}" else ", escondida") + ". " + rowMeta(r, false)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(shape).background(BackgroundCard2)
            .semantics(mergeDescendants = true) { contentDescription = desc; stateDescription = if (r.appear) "Na Início" else "Escondida" }
            .catRowClick(Role.Switch, enabled = !r.orphan, onClick = a.tap)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StateCellPhone(r.appear, number)
        Column(Modifier.weight(1f).alpha(if (r.appear) 1f else .66f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.title.ifBlank { "Catálogo" }, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.isNew) Text(
                    "Nova", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(Accent.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            Text(rowMeta(r, false), color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (r.orphan) {
            JoabButton("Tirar", a.drop, icon = Icons.Default.Delete, tone = ButtonTone.DANGER, compact = true)
        } else {
            Switch(
                checked = r.appear, onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = Accent,
                    uncheckedThumbColor = TextTertiary, uncheckedTrackColor = BackgroundCard2, uncheckedBorderColor = Border,
                ),
            )
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, "Mais opções de ${r.title}", tint = TextSecondary)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    @Composable fun item(label: String, icon: ImageVector, run: () -> Unit) = DropdownMenuItem(
                        text = { Text(label) }, leadingIcon = { Icon(icon, null) }, onClick = { menu = false; run() },
                    )
                    item("Renomear", Icons.Default.Edit, a.rename)
                    if (r.movable) {
                        item("Para o topo", Icons.Default.VerticalAlignTop, a.top)
                        item("Para o fim", Icons.Default.VerticalAlignBottom, a.bottom)
                    }
                    if (r.pref.ref != null && !r.orphan) {
                        if (r.canHero && heroKey != r.key) item("Usar no destaque", Icons.Default.Movie, a.hero)
                        item("Escolher fileiras do ${r.addonName}", Icons.Default.GridView, a.rows)
                    }
                    if (r.collectionId != null) {
                        if (r.firstFolder != null) item("Ver as pastas", Icons.Default.Folder, a.folders)
                        item(if (r.pinToTop) "Soltar do topo" else "Fixar no topo", Icons.Default.PushPin) { a.pin(!r.pinToTop) }
                        item("Abrir Coleções", Icons.Default.Layers, a.collections)
                    }
                }
            }
        }
    }
}

@Composable
private fun StateCellPhone(appear: Boolean, number: Int?) {
    Column(Modifier.width(54.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (appear) {
            Icon(Icons.Default.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
            Text("${number ?: ""}", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        } else {
            Icon(Icons.Default.RadioButtonUnchecked, null, tint = TextTertiary, modifier = Modifier.size(20.dp))
            Text("escondida", color = TextTertiary, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

// ── Resumo da direita ────────────────────────────────────────────────────────

@Composable
private fun SummaryPanel(st: HState, tv: Boolean) {
    SummaryCard {
        Eyebrow("Sua Início agora")
        Spacer(Modifier.height(10.dp))
        val on = st.appearing
        on.take(5).forEachIndexed { i, r ->
            Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${i + 1}", color = TextTertiary, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                Text(r.title, color = TextPrimary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (on.size > 5) Text("e mais ${catNum(on.size - 5)}", color = TextTertiary, fontSize = 13.sp, modifier = Modifier.padding(start = 30.dp, top = 2.dp))
        if (on.isEmpty()) Text("Nada aparece. Ligue uma fileira ou as sugestões.", color = TextTertiary, fontSize = 13.sp)
        Spacer(Modifier.height(12.dp))
        SummaryRow("Destaque", st.heroLabel)
        SummaryRow("Sugestões do JOTV", if (st.catalogOn) "Ligadas" else "Desligadas", if (st.catalogOn) Ok else DangerText)
        SummaryRow("Escondidas", catNum(st.hiddenCount))
        Spacer(Modifier.height(12.dp))
        Text(
            if (tv) "OK mostra ou esconde. Direita move, renomeia e abre Mais."
            else "Toque na linha para mostrar ou esconder. O menu dos três pontos renomeia e move.",
            color = TextTertiary, fontSize = 13.sp, lineHeight = 18.sp,
        )
    }
}

// ── Painéis e diálogos ───────────────────────────────────────────────────────

@Composable
private fun HeroPanel(st: HState, onPick: (HomeCustomizeViewModel.HeroOption) -> Unit, onDismiss: () -> Unit) {
    val opts = st.heroOptions
    var query by remember { mutableStateOf("") }
    val q = query.trim().lowercase()
    val list = if (q.isEmpty()) opts else opts.filter { it.key.isEmpty() || it.label.lowercase().contains(q) }
    val lazy = rememberLazyListState(initialFirstVisibleItemIndex = (list.indexOfFirst { it.key == st.heroSource }.coerceAtLeast(0) - 2).coerceAtLeast(0))
    val fr = remember { FocusRequester() }
    CatRequestFocus(fr, key = q)
    SidePanelDialog("Destaque da Início", onDismiss, sub = "O banner grande do topo") {
        if (opts.size > 20) {
            TvTextFieldGate(Modifier.fillMaxWidth()) { gate ->
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null, tint = TextTertiary) },
                    placeholder = { Text("Buscar fileira entre ${opts.size - 1}", color = TextTertiary, fontSize = 14.sp) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 15.sp),
                    shape = RoundedCornerShape(14.dp), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent,
                        focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                    ),
                    modifier = gate,
                )
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = lazy, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(list, key = { it.key }) { o ->
                val on = o.key == st.heroSource
                PanelItem(
                    o.label, onClick = { onPick(o) },
                    modifier = if (on) Modifier.focusRequester(fr) else Modifier,
                    sub = if (o.key.isEmpty()) "Em alta das sugestões, ou a primeira fileira sem elas" else null,
                    tag = o.tag, selected = on,
                )
            }
        }
    }
}

@Composable
private fun MorePanel(
    r: HRow,
    st: HState,
    onDismiss: () -> Unit,
    onHero: () -> Unit,
    onRows: () -> Unit,
    onFolders: () -> Unit,
    onPin: () -> Unit,
    onCollections: () -> Unit,
) {
    val first = remember { FocusRequester() }
    CatRequestFocus(first)
    val sub = if (r.collectionId != null) "Coleção com " + com.joabtv.app.ui.collections.countOf(r.folders, "pasta", "pastas")
    else "Do addon ${r.addonName}, " + if (r.pref.ref?.type == "series") "séries" else "filmes"
    SidePanelDialog(r.title, onDismiss, sub = sub) {
        val items = buildList<Triple<String, ImageVector, () -> Unit>> {
            if (r.pref.ref != null) {
                if (r.canHero && st.heroSource != r.key) add(Triple("Usar no destaque", Icons.Default.Movie, onHero))
                add(Triple("Escolher fileiras do ${r.addonName}", Icons.Default.GridView, onRows))
            } else {
                if (r.firstFolder != null) add(Triple("Ver as pastas", Icons.Default.Folder, onFolders))
                add(Triple(if (r.pinToTop) "Soltar do topo" else "Fixar no topo", Icons.Default.PushPin, onPin))
                add(Triple("Abrir Coleções", Icons.Default.Layers, onCollections))
            }
        }
        items.forEachIndexed { i, (label, icon, run) ->
            PanelItem(label, run, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier, icon = icon)
        }
    }
}

@Composable
private fun RenameDialog(r: HRow, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(r.title) }
    val first = remember { FocusRequester() }
    CatRequestFocus(first)
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(20.dp)).background(BackgroundCard).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Nome da fileira", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            TvTextFieldGate(Modifier.fillMaxWidth().focusRequester(first), label = "Nome da fileira") { gate ->
                OutlinedTextField(
                    value = text, onValueChange = { text = it.take(60) }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = TextPrimary, fontSize = 16.sp),
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent,
                        focusedContainerColor = Surface2, unfocusedContainerColor = Surface2,
                    ),
                    modifier = gate,
                )
            }
            Text("Original ${r.original}. Vazio volta ao original.", color = TextTertiary, fontSize = 13.sp)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                JoabButton("Cancelar", onDismiss, compact = true)
                JoabButton("Salvar nome", { onSave(text) }, icon = Icons.Default.Check, tone = ButtonTone.PRIMARY, compact = true)
            }
        }
    }
}
