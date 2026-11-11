package com.joabtv.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joabtv.app.data.plugins.*
import com.joabtv.app.ui.common.*
import com.joabtv.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun PluginsScreen(onBack: () -> Unit, onAddons: () -> Unit, onLive: () -> Unit, viewModel: PluginsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = viewModel.repository
    var input by remember { mutableStateOf(false) }
    var pastedBackup by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("all") }
    var selectedRepo by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("") }
    var choice by remember { mutableStateOf<String?>(null) }
    var provider by remember { mutableStateOf<Pair<PluginRepo, PluginProvider>?>(null) }
    var sourceTest by remember { mutableStateOf<Pair<String, String>?>(null) }
    var repo by remember { mutableStateOf<PluginRepo?>(null) }
    var remove by remember { mutableStateOf<PluginRepo?>(null) }
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(viewModel.backup()) } ?: error("Arquivo não disponível") } }
            Toast.makeText(context, if (result.isSuccess) "Backup salvo sem senhas e sessões." else "Não foi possível salvar o backup.", Toast.LENGTH_LONG).show()
        }
    }
    val importBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val data = runCatching { withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                    val output = StringBuilder(); val buffer = CharArray(8192)
                    while (true) { val n = reader.read(buffer); if (n < 0) break; require(output.length + n <= 2 * 1024 * 1024); output.append(buffer, 0, n) }
                    output.toString()
                } ?: error("Arquivo não disponível")
            } }
            data.onSuccess(viewModel::restore).onFailure { Toast.makeText(context, "Não foi possível ler este backup.", Toast.LENGTH_LONG).show() }
        }
    }
    val rows = remember(state, query, filter, selectedRepo, selectedType) {
        val normalized = java.text.Normalizer.normalize(query.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        state.repos.flatMap { r -> r.providers.map { r to it } }.filter { (r, p) ->
            (selectedRepo.isBlank() || r.url == selectedRepo) && (selectedType.isBlank() || selectedType in p.types) &&
                (filter != "enabled" || p.enabled && r.enabled) && (filter != "disabled" || !p.enabled || !r.enabled) &&
                (filter != "error" || p.status?.ok == false) &&
                (normalized.isBlank() || normalized in java.text.Normalizer.normalize("${p.name} ${p.description} ${r.name}".lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), ""))
        }
    }
    val total = state.repos.sumOf { it.providers.size }
    val active = if (!state.enabled) 0 else state.repos.filter { it.enabled }.sumOf { r -> r.providers.count { it.enabled && it.available } }
    Column(Modifier.fillMaxSize().background(Background).topInsetsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = pagePadding(), vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!LocalIsTv.current) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar", tint = TextPrimary) }
            PageHeader("Plugins", Modifier.weight(1f), sub = "Instale repositórios e escolha quais provedores buscam suas fontes.")
        }
        val summary: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(BackgroundCard).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Neste perfil", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("$active de $total provedores ativos", color = TextPrimary, fontSize = 17.sp)
                Text("${state.repos.size} repositórios. A ordem dos repositórios e provedores define a prioridade das fontes.", color = TextSecondary, fontSize = 14.sp)
                JoabButton(if (state.enabled) "Pausar plugins" else "Ativar plugins", { repository.configure(enabled = !state.enabled) }, compact = true)
                Text("Outras fontes", color = TextPrimary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                JoabButton("Addons Stremio", onAddons, compact = true, icon = Icons.Default.Extension)
                JoabButton("Canais ao vivo", onLive, compact = true, icon = Icons.Default.LiveTv)
                Text("Os provedores são executados neste aparelho. A disponibilidade das fontes depende de cada provedor.", color = TextSecondary, fontSize = 14.sp)
                Text("Senhas e sessões ficam neste perfil e fora do backup.", color = TextSecondary, fontSize = 13.sp)
            }
        }
        ContentWithSummary(summary) { below ->
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = pagePadding()), contentPadding = PaddingValues(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChipPill("Adicionar por link", false, { input = true }, icon = Icons.Default.Add, busy = "install" in ui.busy) }
                        item { FilterChipPill("Atualizar todos", false, viewModel::refreshAll, icon = Icons.Default.Refresh, busy = "refresh" in ui.busy) }
                        item { FilterChipPill("Preferências e backup", false, { choice = "options" }, icon = Icons.Default.Tune) }
                    }
                }
                if (ui.busy.isNotEmpty()) item { Row(Modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(24.dp), color = Accent); Text(if ("install" in ui.busy) "Instalando…" else "Carregando plugins…", color = TextSecondary) } }
                ui.message?.let { item { Text(it, color = TextSecondary, modifier = Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(12.dp)).padding(14.dp)) } }
                ui.error?.let { item { Text(it, color = TextPrimary, modifier = Modifier.fillMaxWidth().background(Surface2, RoundedCornerShape(12.dp)).padding(14.dp)) } }
                item {
                    OutlinedTextField(query, { query = it.take(100) }, singleLine = true, label = { Text("Buscar provedor ou repositório") }, modifier = Modifier.fillMaxWidth(), colors = pluginTextColors())
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item { FilterChipPill(state.repos.firstOrNull { it.url == selectedRepo }?.name ?: "Todos os repositórios", selectedRepo.isNotBlank(), { choice = "repos" }) }
                        item { FilterChipPill(when (filter) { "enabled" -> "Ativados"; "disabled" -> "Desativados"; "error" -> "Com erro"; else -> "Todos os estados" }, filter != "all", { choice = "states" }) }
                        item { FilterChipPill(if (selectedType.isBlank()) "Filmes e séries" else if (selectedType == "movie") "Filmes" else "Séries", selectedType.isNotBlank(), { choice = "types" }) }
                        if (query.isNotBlank() || selectedRepo.isNotBlank() || selectedType.isNotBlank() || filter != "all") item { FilterChipPill("Limpar filtros", false, { query = ""; selectedRepo = ""; selectedType = ""; filter = "all" }, icon = Icons.Default.Close) }
                    }
                }
                if (state.repos.isEmpty()) item {
                    Column(Modifier.fillMaxWidth().padding(vertical = 30.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Escolha seus provedores", color = TextPrimary, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                        Text("Cole o link de um repositório Nuvio, addon Stremio ou arquivo JavaScript para começar. Os provedores novos começam desativados.", color = TextSecondary, fontSize = 16.sp)
                    }
                } else if (rows.isEmpty()) item { Text("Nenhum provedor neste filtro. Use Limpar filtros para ver todos.", color = TextSecondary, modifier = Modifier.padding(16.dp)) }
                items(rows, key = { (r, p) -> repository.key(r, p) }) { (r, p) ->
                    var focused by remember { mutableStateOf(false) }
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (p.enabled && r.enabled && state.enabled) AccentSoft else BackgroundCard)
                        .border(if (focused) 2.dp else 0.dp, if (focused) TextPrimary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                        .onFocusChanged { focused = it.isFocused }.clickable { provider = r to p }.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Default.Extension, null, tint = TextSecondary, modifier = Modifier.size(30.dp))
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(p.name, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                            Text(r.name + " · " + p.types.joinToString(" · ") { if (it == "movie") "Filmes" else if (it == "tv" || it == "series") "Séries" else it }, color = TextSecondary, fontSize = 13.sp)
                            Text(p.status?.takeIf { !it.ok }?.message ?: p.description, color = TextSecondary, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (!p.available) "Indisponível" else if (!state.enabled || !r.enabled) "Pausado" else if (p.enabled) "Ativado" else "Desativado", color = if (p.enabled && r.enabled && state.enabled && p.available) Accent else TextSecondary, fontSize = 13.sp)
                    }
                }
                if (state.repos.isNotEmpty()) item { JoabButton("Gerenciar repositórios", { choice = "manage" }, compact = true, icon = Icons.Default.Layers) }
                item { below?.invoke() }
            }
        }
    }
    if (input) PluginInputDialog("Adicionar plugin ou addon", "Link de repositório, manifest.json ou arquivo .js", "Instalar", "install" in ui.busy, { input = false }) { value -> input = false; viewModel.install(value) }
    if (pastedBackup) PluginInputDialog("Restaurar plugins", "JSON do backup do JOTV", "Restaurar", false, { pastedBackup = false }, multiline = true) { value -> pastedBackup = false; viewModel.restore(value) }
    ui.editor?.let { editor -> PluginSettingsDialog(editor, ui.error, viewModel::closeEditor, viewModel::save) }
    provider?.let { (r, p) ->
        val id = repository.key(r, p)
        PluginMenu(p.name, listOf(
            PluginMenuItem(if (p.enabled) "Desativar provedor" else "Ativar provedor", enabled = p.available) { repository.toggle(id, !p.enabled) },
            PluginMenuItem("Configurar") { viewModel.edit(id) }, PluginMenuItem("Testar carregamento") { viewModel.test(id) },
            PluginMenuItem("Testar fontes para um título") { sourceTest = id to p.name },
            PluginMenuItem("Subir prioridade") { repository.move(r.url, p.id, -1) }, PluginMenuItem("Descer prioridade") { repository.move(r.url, p.id, 1) },
            PluginMenuItem("Gerenciar ${r.name}") { repo = r },
            PluginMenuItem("Versão ${p.version.ifBlank { "não informada" }}", p.languages.joinToString(" · ") + "\n" + p.formats.joinToString(" · "), enabled = false),
            PluginMenuItem(p.status?.message ?: "Ainda não executado.", p.status?.let { "${it.ms} ms" }.orEmpty(), enabled = false)
        )) { provider = null }
    }
    sourceTest?.let { (id, title) -> PluginSourceTestDialog(title, { sourceTest = null }) { tmdb, series, season, episode ->
        sourceTest = null
        viewModel.perform("test:$id") { val count = repository.testSources(id, if (series) "tv" else "movie", tmdb, season, episode); if (count > 0) "$count fontes encontradas." else "Nenhuma fonte para este título. Confira as configurações do provedor." }
    } }
    repo?.let { r -> PluginMenu(r.name, listOf(
        PluginMenuItem("Atualizar repositório") { viewModel.refresh(r.url) },
        PluginMenuItem(if (r.enabled) "Pausar repositório" else "Retomar repositório") { repository.toggleRepo(r.url, !r.enabled) },
        PluginMenuItem("Ativar todos os provedores disponíveis") { repository.toggleAll(r.url, true) }, PluginMenuItem("Desativar todos os provedores") { repository.toggleAll(r.url, false) },
        PluginMenuItem("Subir prioridade do repositório") { repository.move(r.url, null, -1) }, PluginMenuItem("Descer prioridade do repositório") { repository.move(r.url, null, 1) },
        PluginMenuItem("Remover repositório") { remove = r }, PluginMenuItem(r.url, r.description, enabled = false)
    )) { repo = null } }
    remove?.let { r -> AlertDialog(onDismissRequest = { remove = null }, title = { Text("Remover ${r.name}?") }, text = { Text("Os provedores e as configurações deste repositório serão removidos deste perfil.") }, confirmButton = { TextButton(onClick = { repository.remove(r.url); remove = null }) { Text("Remover") } }, dismissButton = { TextButton(onClick = { remove = null }) { Text("Cancelar") } }, containerColor = BackgroundCard, titleContentColor = TextPrimary, textContentColor = TextSecondary) }
    choice?.let { menu ->
        val items = when (menu) {
            "repos" -> listOf(PluginMenuItem("Todos", selected = selectedRepo.isBlank()) { selectedRepo = "" }) + state.repos.map { r -> PluginMenuItem(r.name, selected = selectedRepo == r.url) { selectedRepo = r.url } }
            "manage" -> state.repos.map { r -> PluginMenuItem(r.name, "${r.providers.size} provedores") { repo = r } }
            "states" -> listOf("all" to "Todos", "enabled" to "Ativados", "disabled" to "Desativados", "error" to "Com erro").map { (key, title) -> PluginMenuItem(title, selected = filter == key) { filter = key } }
            "types" -> listOf("" to "Todos", "movie" to "Filmes", "tv" to "Séries").map { (key, title) -> PluginMenuItem(title, selected = selectedType == key) { selectedType = key } }
            "timeout" -> listOf(15, 30, 45, 60, 90, 120).map { n -> PluginMenuItem("$n segundos", selected = state.timeout == n * 1000L) { repository.configure(timeout = n * 1000L) } }
            "concurrency" -> listOf(32, 2, 1).map { n -> PluginMenuItem(if (n == 32) "Buscar em paralelo" else if (n == 1) "Um de cada vez" else "Até dois ao mesmo tempo", if (n == 32) "As fontes aparecem conforme cada provedor responde." else "", selected = state.concurrency == n) { repository.configure(concurrency = n) } }
            else -> listOf(
                PluginMenuItem(if (state.enabled) "Pausar todos os plugins" else "Ativar plugins") { repository.configure(enabled = !state.enabled) },
                PluginMenuItem(if (state.autoUpdate) "Desativar atualização automática" else "Ativar atualização automática", "Conferir a lista uma vez por dia.") { repository.configure(autoUpdate = !state.autoUpdate) },
                PluginMenuItem("Tempo máximo por provedor", "${state.timeout / 1000} segundos") { choice = "timeout" },
                PluginMenuItem("Provedores simultâneos", state.concurrency.toString()) { choice = "concurrency" },
                PluginMenuItem("Limpar cache dos provedores", "Os arquivos serão baixados novamente quando necessário.") { viewModel.perform("cache") { repository.clearCache(); "Cache dos provedores limpo." } },
                PluginMenuItem("Exportar backup sem senhas") { createBackup.launch("JOTV-plugins.json") },
                PluginMenuItem("Importar arquivo de backup") { importBackup.launch(arrayOf("application/json", "text/plain")) },
                PluginMenuItem("Colar backup JSON") { pastedBackup = true }, PluginMenuItem("Gerenciar repositórios") { choice = "manage" }
            )
        }
        val current = menu
        PluginMenu(when (menu) { "repos", "manage" -> "Repositórios"; "states" -> "Estado do provedor"; "types" -> "Tipo de conteúdo"; "timeout" -> "Tempo por provedor"; "concurrency" -> "Execução simultânea"; else -> "Preferências e backup" }, items.map { item -> item.copy(action = item.action?.let { action -> { if (choice == current) choice = null; action() } }) }) { choice = null }
    }
}

private data class PluginMenuItem(val label: String, val description: String = "", val selected: Boolean = false, val enabled: Boolean = true, val action: (() -> Unit)? = null)
@Composable
private fun PluginMenu(title: String, items: List<PluginMenuItem>, closeOnSelect: Boolean = true, onClose: () -> Unit) {
    val initialFocus = remember { FocusRequester() }
    val first = items.indexOfFirst { it.enabled && it.action != null }
    LaunchedEffect(title) { kotlinx.coroutines.delay(100); if (first >= 0) runCatching { initialFocus.requestFocus() } }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.9f).widthIn(max = 760.dp).fillMaxHeight(.85f).background(BackgroundCard, RoundedCornerShape(16.dp)).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fechar", tint = TextPrimary) } }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items.size) { index ->
                    val item = items[index]
                    var focused by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (item.selected) AccentSoft else Surface2)
                        .border(if (focused) 2.dp else 0.dp, if (focused) TextPrimary else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(12.dp))
                        .onFocusChanged { focused = it.isFocused }.then(if (index == first) Modifier.focusRequester(initialFocus) else Modifier)
                        .then(if (item.enabled && item.action != null) Modifier.clickable { if (closeOnSelect) onClose(); item.action.invoke() } else Modifier).padding(16.dp)) {
                        Text(item.label + if (item.selected) " · Selecionado" else "", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        if (item.description.isNotBlank()) Text(item.description, color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
    }
}
@Composable
private fun pluginTextColors() = OutlinedTextFieldDefaults.colors(focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, focusedBorderColor = Accent, unfocusedBorderColor = Border, cursorColor = Accent)
@Composable
private fun PluginInputDialog(title: String, label: String, ok: String, busy: Boolean, onClose: () -> Unit, multiline: Boolean = false, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onClose, title = { Text(title) }, text = { OutlinedTextField(value, { value = it.take(if (multiline) 2 * 1024 * 1024 else 8192) }, label = { Text(label) }, singleLine = !multiline, maxLines = if (multiline) 8 else 1, colors = pluginTextColors()) },
        confirmButton = { TextButton(onClick = { onSave(value) }, enabled = !busy && value.isNotBlank()) { Text(ok) } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } }, containerColor = BackgroundCard, titleContentColor = TextPrimary, textContentColor = TextSecondary)
}
@Composable
private fun PluginSourceTestDialog(title: String, onClose: () -> Unit, onTest: (Int, Boolean, Int?, Int?) -> Unit) {
    var tmdb by remember { mutableStateOf("") }
    var series by remember { mutableStateOf(false) }
    var season by remember { mutableStateOf("1") }
    var episode by remember { mutableStateOf("1") }
    AlertDialog(onDismissRequest = onClose, title = { Text("Testar $title") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Informe o identificador do título no TMDB. O teste não ativa o provedor.")
            OutlinedTextField(tmdb, { tmdb = it.filter(Char::isDigit).take(9) }, label = { Text("Identificador TMDB") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = pluginTextColors())
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(series, { series = it }); Text("Série") }
            if (series) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(season, { season = it.filter(Char::isDigit).take(4) }, label = { Text("Temporada") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = pluginTextColors())
                OutlinedTextField(episode, { episode = it.filter(Char::isDigit).take(4) }, label = { Text("Episódio") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = pluginTextColors())
            }
        }
    }, confirmButton = { TextButton(enabled = (tmdb.toIntOrNull() ?: 0) > 0 && (!series || (season.toIntOrNull() ?: 0) > 0 && (episode.toIntOrNull() ?: 0) > 0), onClick = { onTest(tmdb.toInt(), series, season.toIntOrNull(), episode.toIntOrNull()) }) { Text("Testar fontes") } }, dismissButton = { TextButton(onClick = onClose) { Text("Cancelar") } }, containerColor = BackgroundCard, titleContentColor = TextPrimary, textContentColor = TextSecondary)
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PluginSettingsDialog(editor: PluginsViewModel.Editor, error: String?, onClose: () -> Unit, onSave: (JSONObject) -> Unit) {
    var values by remember(editor.id) { mutableStateOf(JSONObject(editor.values)) }
    var select by remember { mutableStateOf<PluginField?>(null) }
    fun set(key: String, value: Any) { values = JSONObject(values.toString()).put(key, value) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(.92f).widthIn(max = 900.dp).fillMaxHeight(.9f).background(BackgroundCard, RoundedCornerShape(16.dp)).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Text(editor.title, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f)); IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fechar configurações", tint = TextPrimary) } }
            Text("As alterações passam a valer ao salvar. Campos privados ficam ocultos.", color = TextSecondary, fontSize = 14.sp, modifier = Modifier.padding(bottom = 12.dp))
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editor.fields.isEmpty()) item { Text("Este provedor não possui configurações.", color = TextSecondary) }
                items(editor.fields) { field ->
                    val value = values.opt(field.key)?.takeUnless { it == JSONObject.NULL } ?: field.defaultValue
                    when (field.type) {
                        "header" -> Text(field.label, color = TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                        "toggle" -> SettingsRow(icon = Icons.Default.Tune, title = field.label, sub = field.description, trailing = { Switch(value == true || value == "true", { set(field.key, it) }, modifier = Modifier.focusProperties { canFocus = false }) }, onClick = { set(field.key, value != true && value != "true") })
                        "select", "multiselect" -> SettingsRow(icon = Icons.Default.List, title = field.label, sub = if (field.type == "multiselect") "${(value as? JSONArray)?.length() ?: 0} opções" else field.options.firstOrNull { it.second.toString() == value?.toString() }?.first ?: "Escolher", onClick = { select = field })
                        else -> Column {
                            OutlinedTextField(value?.toString().orEmpty(), { set(field.key, it.take(16384)) }, label = { Text(field.label) }, singleLine = field.type == "number" || field.type == "slider", maxLines = 4,
                                visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions(keyboardType = if (field.type == "number" || field.type == "slider") KeyboardType.Decimal else if (field.secret) KeyboardType.Password else KeyboardType.Text), modifier = Modifier.fillMaxWidth(), colors = pluginTextColors())
                            if (field.description.isNotBlank()) Text(field.description, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 5.dp))
                        }
                    }
                }
            }
            error?.let { Text(it, color = TextPrimary, modifier = Modifier.padding(vertical = 8.dp)) }
            FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                JoabButton("Restaurar padrão", { values = JSONObject().apply { editor.fields.forEach { f -> if (f.key.isNotBlank() && f.defaultValue != null) put(f.key, f.defaultValue) } } }, compact = true)
                JoabButton("Salvar configurações", { onSave(values) }, compact = true)
            }
        }
    }
    select?.let { field ->
        PluginMenu(field.label, field.options.map { option -> PluginMenuItem(option.first, selected = if (field.type == "multiselect") values.optJSONArray(field.key)?.let { array -> (0 until array.length()).any { array.optString(it) == option.second.toString() } } == true else values.opt(field.key)?.toString() == option.second.toString()) {
            if (field.type == "multiselect") { val selected = values.optJSONArray(field.key) ?: JSONArray(); val old = (0 until selected.length()).map { selected.opt(it) }; val next = if (old.any { it.toString() == option.second.toString() }) old.filterNot { it.toString() == option.second.toString() } else old + option.second; set(field.key, JSONArray(next)) }
            else set(field.key, option.second)
        } }, closeOnSelect = field.type != "multiselect") { select = null }
    }
}
