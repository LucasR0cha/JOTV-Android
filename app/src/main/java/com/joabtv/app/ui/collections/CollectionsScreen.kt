package com.joabtv.app.ui.collections

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.ImportPart
import com.joabtv.app.data.collections.MediaCollection
import com.joabtv.app.ui.common.ButtonTone
import com.joabtv.app.ui.common.JoabButton
import com.joabtv.app.ui.common.LocalIsTv
import com.joabtv.app.ui.common.TvTextFieldGate
import com.joabtv.app.ui.common.topInsetsPadding
import com.joabtv.app.ui.settings.AccountPanel
import com.joabtv.app.ui.settings.AccountOverlays
import com.joabtv.app.ui.settings.AccountsViewModel
import com.joabtv.app.ui.settings.liveFocusSoon
import com.joabtv.app.ui.theme.*

/**
 * Coleções do perfil, no formato do Nuvio. Traz do Nuvio, de um link ou de um texto colado, e na TV também
 * lista, fixa no topo e apaga. Criar, editar, ordenar e exportar ficam no celular. O primeiro botão segue a
 * conta Nuvio (Entrar, Trazer ou Atualizar).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onEdit: (collectionId: String?) -> Unit,
    onOpenHome: () -> Unit = onBack,
    viewModel: CollectionsViewModel = hiltViewModel(),
    accounts: AccountsViewModel = hiltViewModel(),
) {
    val st by viewModel.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val tv = LocalIsTv.current
    val pad = com.joabtv.app.ui.common.pagePadding()
    var importMode by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<MediaCollection?>(null) }
    var panel by remember { mutableStateOf<AccountPanel?>(null) }
    val folders = st.collections.sumOf { it.folders.size }
    val nuvioFocus = remember { FocusRequester() }
    val firstItem = remember { FocusRequester() }

    fun export() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Coleções do JOTV")
            putExtra(Intent.EXTRA_TEXT, viewModel.exportJson())
        }
        ctx.startActivity(Intent.createChooser(send, "Exportar coleções"))
    }

    /** Sem conta entra já com a intenção de trazer só coleções. Com conta traz do perfil escolhido. */
    fun fromNuvio() {
        if (st.nuvioLogged) viewModel.importNuvio() else accounts.startCode(Account.NUVIO, ImportPart.COLLECTIONS)
    }

    // Foco inicial na TV. Já trouxe do Nuvio, na primeira coleção. Senão, no botão da conta.
    LaunchedEffect(Unit) {
        if (tv) liveFocusSoon(if (st.nuvioPulledOk && st.collections.isNotEmpty()) firstItem else nuvioFocus)
    }

    Column(Modifier.fillMaxSize().background(Background).topInsetsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = pad, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!tv) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = TextPrimary)
            }
            com.joabtv.app.ui.common.PageHeader(
                "Coleções", Modifier.weight(1f),
                sub = (if (st.collections.isEmpty()) "Nenhuma coleção ainda"
                else countOf(st.collections.size, "coleção", "coleções") + " com " + countOf(folders, "pasta", "pastas")) +
                    ". Vale para o perfil ${st.profileName}.",
            )
            if (!tv) com.joabtv.app.ui.common.GlassCircleButton(Icons.Default.Add, { onEdit(null) }, contentDescription = "Nova coleção")
        }
        st.nuvioPulled?.let {
            Text(it, color = TextTertiary, fontSize = 14.sp, modifier = Modifier.padding(start = pad + 8.dp, end = pad, bottom = 4.dp))
        }

        val summary: @Composable () -> Unit = {
            com.joabtv.app.ui.common.SummaryCard {
                Text("Coleções", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))
                com.joabtv.app.ui.common.SummaryRow("Coleções", "${st.collections.size}")
                com.joabtv.app.ui.common.SummaryRow("Pastas", "$folders")
                com.joabtv.app.ui.common.SummaryRow("Fixadas no topo", "${st.collections.count { it.pinToTop }}")
                com.joabtv.app.ui.common.SummaryRow("Conta Nuvio", if (st.nuvioLogged) "Conectada" else "Não conectada", if (st.nuvioLogged) Ok else TextSecondary)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Usa o mesmo formato do Nuvio. O texto exportado daqui entra lá, e o de lá entra aqui. Cada coleção vira uma fileira da Início, que dá para ordenar em Minha Início.",
                    color = TextTertiary, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }

        com.joabtv.app.ui.common.ContentWithSummary(summary) { below ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = pad, end = pad, top = 6.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        JoabButton(
                            st.nuvioLabel, ::fromNuvio, Modifier.focusRequester(nuvioFocus),
                            icon = if (!st.nuvioLogged) Icons.Default.Key else if (st.nuvioPulledOk) Icons.Default.Refresh else Icons.Default.Download,
                            tone = ButtonTone.PRIMARY, busy = st.busy == "nuvio" || st.nuvioRunning, busyLabel = "Trazendo…",
                            enabled = st.busy == null || st.busy == "nuvio", compact = true,
                        )
                        JoabButton("Abrir link", { importMode = "url" }, icon = Icons.Default.Link, compact = true)
                        JoabButton("Colar texto", { importMode = "paste" }, icon = Icons.Default.Add, compact = true)
                        if (!tv) {
                            JoabButton("Nova coleção", { onEdit(null) }, icon = Icons.Default.Add, compact = true)
                            JoabButton("Exportar", ::export, icon = Icons.Default.Share, enabled = st.collections.isNotEmpty(), compact = true)
                        }
                    }
                }
                item {
                    CollectionCard {
                        Text(
                            "Coleção é uma fileira com pastas, como Marvel ou Anime. Crie no celular, no JOTV ou no Nuvio, e traga para cá." +
                                if (tv) " Para criar e editar, use o celular." else "",
                            color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                        )
                    }
                }
                (st.error ?: st.message)?.let { msg ->
                    item {
                        CollectionCard(highlight = true) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(msg, color = if (st.error != null) DangerText else TextPrimary, fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = viewModel::clearMessage) { Text("Ok", color = Accent, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
                if (st.collections.isNotEmpty()) item { com.joabtv.app.ui.common.SettingsSection("Suas coleções") }
                itemsIndexed(st.collections, key = { _, c -> c.id }) { i, c ->
                    if (tv) TvCollectionItem(
                        c, i + 1, if (i == 0) Modifier.focusRequester(firstItem) else Modifier,
                        onPin = { viewModel.togglePin(c.id) }, onDelete = { deleting = c },
                    ) else CollectionItem(
                        c,
                        canUp = i > 0, canDown = i < st.collections.lastIndex,
                        onEdit = { onEdit(c.id) },
                        onUp = { viewModel.move(c.id, -1) },
                        onDown = { viewModel.move(c.id, 1) },
                        onPin = { viewModel.togglePin(c.id) },
                        onDelete = { deleting = c },
                    )
                }
                below?.let { b -> item { Spacer(Modifier.height(12.dp)); b() } }
            }
        }
    }

    importMode?.let { mode ->
        CollectionImportDialog(
            st = st, initialMode = mode,
            onPaste = viewModel::importText, onUrl = viewModel::importUrl,
            onNuvio = { importMode = null; fromNuvio() },
            onDismiss = { importMode = null },
        )
        // Trouxe, fecha o diálogo. A mensagem fica na tela.
        LaunchedEffect(st.message) { if (st.message != null) importMode = null }
    }
    AccountOverlays(accounts, panel, { panel = it }, onOpenHome = onOpenHome)

    deleting?.let { c ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = BackgroundCard,
            title = { Text("Apagar ${c.title}?", color = TextPrimary, fontWeight = FontWeight.ExtraBold) },
            text = { Text("A coleção sai da Início deste perfil, com " + countOf(c.folders.size, "pasta", "pastas") + ".", color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp) },
            confirmButton = { TextButton(onClick = { viewModel.remove(c.id); deleting = null }) { Text("Apagar", color = DangerText, fontWeight = FontWeight.ExtraBold) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar", color = TextSecondary) } },
        )
    }
}

private fun metaOf(c: MediaCollection): String {
    val sources = c.folders.sumOf { it.sources.size }
    return countOf(c.folders.size, "pasta", "pastas") + " · " + countOf(sources, "fonte", "fontes") + " · " + if (c.tabbed) "abas" else "fileiras"
}

/** Coleção na TV. Mostra o que ela tem e deixa fixar no topo e apagar, com botões que o controle alcança. */
@Composable
private fun TvCollectionItem(c: MediaCollection, number: Int, modifier: Modifier, onPin: () -> Unit, onDelete: () -> Unit) {
    CollectionCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("$number", color = TextTertiary, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Column(Modifier.weight(1f)) {
                Text(c.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(metaOf(c), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                if (c.pinToTop || c.folders.isEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (c.pinToTop) CollectionTag("Fixada no topo")
                    if (c.folders.isEmpty()) CollectionTag("Sem pastas, não aparece na Início", TextTertiary)
                }
            }
            JoabButton(
                if (c.pinToTop) "Soltar do topo" else "Fixar no topo", onPin, modifier,
                icon = if (c.pinToTop) Icons.Default.PushPin else Icons.Outlined.PushPin,
                tone = if (c.pinToTop) ButtonTone.ON else ButtonTone.SECONDARY, compact = true,
            )
            JoabButton("Apagar", onDelete, icon = Icons.Default.Delete, compact = true)
        }
    }
}

@Composable
private fun CollectionItem(
    c: MediaCollection,
    canUp: Boolean,
    canDown: Boolean,
    onEdit: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit,
    onPin: () -> Unit,
    onDelete: () -> Unit,
) {
    CollectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(c.title, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(metaOf(c), color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                if (c.pinToTop || c.folders.isEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (c.pinToTop) CollectionTag("Fixada no topo")
                    if (c.folders.isEmpty()) CollectionTag("Sem pastas, não aparece na Início", TextTertiary)
                }
            }
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Editar", tint = TextPrimary) }
            IconButton(onClick = onUp, enabled = canUp) { Icon(Icons.Default.KeyboardArrowUp, "Subir", tint = if (canUp) TextPrimary else TextTertiary) }
            IconButton(onClick = onDown, enabled = canDown) { Icon(Icons.Default.KeyboardArrowDown, "Descer", tint = if (canDown) TextPrimary else TextTertiary) }
            IconButton(onClick = onPin) {
                Icon(if (c.pinToTop) Icons.Default.PushPin else Icons.Outlined.PushPin, if (c.pinToTop) "Soltar do topo" else "Fixar no topo", tint = if (c.pinToTop) Accent else TextPrimary)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Apagar", tint = DangerText) }
        }
    }
}

/**
 * Trazer coleções por texto colado, por link ou pela conta Nuvio. O botão gira e trava até a resposta. A aba do
 * Nuvio segue a conta (Entrar no Nuvio, Trazer do Nuvio ou Atualizar do Nuvio) e nunca deixa a pessoa sem saída.
 */
@Composable
fun CollectionImportDialog(
    st: CollectionsViewModel.Ui,
    initialMode: String,
    onPaste: (String) -> Unit,
    onUrl: (String) -> Unit,
    onNuvio: () -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(initialMode) }
    var text by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    // Na TV o foco já nasce na aba em uso, e não na primeira.
    val chipFocus = remember { mapOf("paste" to FocusRequester(), "url" to FocusRequester(), "nuvio" to FocusRequester()) }
    if (LocalIsTv.current) LaunchedEffect(Unit) { liveFocusSoon(chipFocus.getValue(initialMode)) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
        focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent,
    )
    AlertDialog(
        onDismissRequest = { if (st.busy == null) onDismiss() },
        containerColor = BackgroundCard,
        title = { Text("Trazer coleções", color = TextPrimary, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.joabtv.app.ui.common.FilterChipPill("Colar texto", mode == "paste", { mode = "paste" }, Modifier.focusRequester(chipFocus.getValue("paste")))
                    com.joabtv.app.ui.common.FilterChipPill("Abrir link", mode == "url", { mode = "url" }, Modifier.focusRequester(chipFocus.getValue("url")))
                    com.joabtv.app.ui.common.FilterChipPill("Conta Nuvio", mode == "nuvio", { mode = "nuvio" }, Modifier.focusRequester(chipFocus.getValue("nuvio")))
                }
                when (mode) {
                    "paste" -> TvTextFieldGate(Modifier.fillMaxWidth(), label = "Texto das coleções") { gate ->
                        OutlinedTextField(
                            value = text, onValueChange = { text = it }, minLines = 4, maxLines = 8,
                            placeholder = { Text("Cole aqui o texto exportado do Nuvio ou do JOTV", color = TextTertiary) },
                            colors = fieldColors, modifier = gate,
                        )
                    }
                    "url" -> {
                        Text(
                            "Endereço que abre o texto exportado do Nuvio ou do JOTV (GitHub, Gist ou o seu site). Até 2 MB.",
                            color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                        )
                        TvTextFieldGate(Modifier.fillMaxWidth(), label = "Link das coleções") { gate ->
                            OutlinedTextField(
                                value = url, onValueChange = { url = it }, singleLine = true,
                                placeholder = { Text("https://exemplo.com/colecoes.json", color = TextTertiary) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                                colors = fieldColors, modifier = gate,
                            )
                        }
                    }
                    else -> {
                        Text(
                            if (!st.nuvioLogged) "Entre na conta Nuvio para trazer as coleções dela. Elas chegam do perfil do Nuvio que você escolher."
                            else "Traz as coleções do perfil do Nuvio escolhido na conta. Para trocar de perfil, use Mais no cartão do Nuvio, em Configurações.",
                            color = TextSecondary, fontSize = 14.sp, lineHeight = 20.sp,
                        )
                        st.nuvioPulled?.let { Text(it, color = TextTertiary, fontSize = 13.sp, lineHeight = 18.sp) }
                    }
                }
                Text("Coleção com o mesmo id de uma que já existe é substituída. As outras ficam.", color = TextTertiary, fontSize = 12.sp, lineHeight = 17.sp)
                st.error?.let { Text(it, color = DangerText, fontSize = 13.sp, lineHeight = 18.sp) }
            }
        },
        confirmButton = {
            when (mode) {
                "paste" -> JoabButton("Trazer", { onPaste(text) }, icon = Icons.Default.Download, tone = ButtonTone.PRIMARY,
                    busy = st.busy == "paste", busyLabel = "Trazendo…", enabled = st.busy == null && text.isNotBlank(), compact = true)
                "url" -> JoabButton("Trazer", { onUrl(url) }, icon = Icons.Default.Download, tone = ButtonTone.PRIMARY,
                    busy = st.busy == "url", busyLabel = "Trazendo…", enabled = st.busy == null && url.isNotBlank(), compact = true)
                else -> JoabButton(st.nuvioLabel, onNuvio,
                    icon = if (!st.nuvioLogged) Icons.Default.Key else if (st.nuvioPulledOk) Icons.Default.Refresh else Icons.Default.Download,
                    tone = ButtonTone.PRIMARY, busy = st.busy == "nuvio" || st.nuvioRunning, busyLabel = "Trazendo…",
                    enabled = st.busy == null || st.busy == "nuvio", compact = true)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = st.busy == null) { Text("Fechar", color = TextSecondary) } },
    )
}
