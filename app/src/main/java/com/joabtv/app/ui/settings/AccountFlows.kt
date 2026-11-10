package com.joabtv.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joabtv.app.data.accounts.Account
import com.joabtv.app.data.accounts.ImportPart
import com.joabtv.app.data.accounts.Load
import com.joabtv.app.data.accounts.PartRecord
import com.joabtv.app.ui.common.ButtonTone
import com.joabtv.app.ui.common.FilterChipPill
import com.joabtv.app.ui.common.JoabButton
import com.joabtv.app.ui.common.LocalIsTv
import com.joabtv.app.ui.common.TvTextFieldGate
import com.joabtv.app.ui.theme.*
import kotlinx.coroutines.launch

// Diálogos e painéis das contas Stremio e Nuvio. Valem nas Configurações e na tela Contas.
// Entrar abre um diálogo que vira o passo "Trazer tudo" sem fechar. Mais e O que veio são painéis.

/** Painel aberto por cima da tela, pelos botões Mais e O que veio do cartão. */
internal sealed interface AccountPanel {
    val account: Account

    data class More(override val account: Account) : AccountPanel
    data class What(override val account: Account) : AccountPanel
}

/** O que cada botão de um cartão de conta faz. [openPanel] abre os painéis. */
internal fun accountAct(vm: AccountsViewModel, a: Account, act: String, openPanel: (AccountPanel) -> Unit, intent: ImportPart? = null) {
    when (act) {
        "login" -> vm.startCode(a, intent)
        "email" -> vm.startEmail(a, intent)
        "all" -> vm.runAndTell(a)
        "retry" -> vm.runAndTell(a, vm.ui.value.log[a]?.failedParts.orEmpty())
        "what" -> openPanel(AccountPanel.What(a))
        "more" -> openPanel(AccountPanel.More(a))
    }
}

/** Só abre link da web. O endereço vem da resposta do serviço e não deve abrir outro tipo de app. */
internal fun openWeb(context: android.content.Context, url: String) {
    runCatching {
        if (!url.startsWith("https://", true) && !url.startsWith("http://", true)) return
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Tudo que abre por cima das contas, no mesmo lugar para as duas telas. Também mostra os avisos curtos que
 * o [AccountsViewModel] manda. [onOpenHome] é o "Ver minha Início".
 */
@Composable
internal fun AccountOverlays(
    vm: AccountsViewModel,
    panel: AccountPanel?,
    onPanel: (AccountPanel?) -> Unit,
    onOpenHome: () -> Unit,
) {
    val st by vm.ui.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.toasts.collect { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() } }
    when (val l = st.login) {
        is AccountsViewModel.LoginFlow.Code -> CodeDialog(l, st.busy != null, vm)
        is AccountsViewModel.LoginFlow.Email -> EmailDialog(l, st.busy != null, vm)
        is AccountsViewModel.LoginFlow.Step -> ImportStepDialog(l, st, vm, onOpenHome)
        null -> Unit
    }
    when (panel) {
        is AccountPanel.More -> MorePanel(panel.account, st, vm, onDismiss = { onPanel(null) }, onWhat = { onPanel(AccountPanel.What(panel.account)) })
        is AccountPanel.What -> WhatPanel(panel.account, st, vm, onDismiss = { onPanel(null) }, onHome = { onPanel(null); onOpenHome() })
        null -> Unit
    }
}

// ── Passo 1. Código para confirmar no celular ───────────────────────────────

@Composable
private fun CodeDialog(c: AccountsViewModel.LoginFlow.Code, busy: Boolean, vm: AccountsViewModel) {
    val tv = LocalIsTv.current
    val ctx = LocalContext.current
    val a = c.account
    val firstFocus = remember { FocusRequester() }
    LiveDialog(
        onDismiss = vm::closeLogin, width = 680.dp,
        actions = {
            LiveDialogActions {
                if (!tv && !c.expired) JoabButton(
                    "Abrir no app ou no site do ${a.label}", { openWeb(ctx, c.url) }, icon = Icons.Default.OpenInNew, tone = ButtonTone.PRIMARY, compact = true,
                )
                if (c.expired) JoabButton(
                    "Gerar outro", { vm.startCode(a, c.intent) }, Modifier.focusRequester(firstFocus), icon = Icons.Default.Refresh,
                    tone = ButtonTone.PRIMARY, busy = busy, busyLabel = "Gerando código…", compact = true,
                ) else JoabButton("Cancelar", vm::closeLogin, Modifier.focusRequester(firstFocus), compact = true)
                JoabButton("Usar e-mail e senha", { vm.startEmail(a, c.intent) }, icon = Icons.Default.Person, compact = true)
            }
        },
    ) {
        LiveDialogTitle("Entrar no ${a.label}")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LiveDialogBody(
                    if (c.expired) "O código venceu. Gere outro para continuar."
                    else if (tv) "No celular, abra ${a.site} com a conta aberta e confirme o código."
                    else "Abra o ${a.label} neste aparelho com a conta aberta e confirme o código.",
                )
                Text(c.url.removePrefix("https://"), color = Accent, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Inter)
                Text(
                    c.code, color = if (c.expired) TextTertiary else TextPrimary, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 6.sp, fontFamily = FontFamily.Monospace,
                )
                if (!c.expired) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Text("Esperando a confirmação no celular", color = TextSecondary, fontSize = 14.sp, fontFamily = Inter)
                }
            }
            // O QR serve para ler com outro aparelho. No celular o link abre direto, então ele sai.
            if (tv && !c.expired) QrCode(
                c.url, Modifier.size(176.dp).clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp),
                "Código QR para entrar no ${a.label}",
            )
        }
    }
    if (tv) LaunchedEffect(c.expired) { liveFocusSoon(firstFocus) }
}

// ── E-mail e senha ──────────────────────────────────────────────────────────

@Composable
private fun EmailDialog(f: AccountsViewModel.LoginFlow.Email, busy: Boolean, vm: AccountsViewModel) {
    val a = f.account
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var showPass by remember { mutableStateOf(false) }
    var serverOpen by remember { mutableStateOf(false) }
    val tv = LocalIsTv.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val loginFocus = remember { FocusRequester() }
    val firstFocus = remember { FocusRequester() }
    val next = if (tv) KeyboardActions(onNext = { keyboard?.hide(); focusManager.moveFocus(FocusDirection.Down) }) else KeyboardActions.Default
    val done = if (tv) KeyboardActions(onDone = { keyboard?.hide(); runCatching { loginFocus.requestFocus() } }) else KeyboardActions.Default
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Accent, unfocusedBorderColor = Border,
        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = Accent,
    )
    val canGo = email.contains('@') && pass.isNotBlank()
    LiveDialog(
        onDismiss = vm::closeLogin, width = 560.dp,
        actions = {
            LiveDialogActions {
                JoabButton(
                    "Entrar", { vm.emailLogin(a, email, pass, server) }, Modifier.focusRequester(loginFocus),
                    icon = Icons.Default.Key, tone = ButtonTone.PRIMARY, busy = busy, busyLabel = "Entrando…", enabled = canGo || busy, compact = true,
                )
                JoabButton("Cancelar", vm::closeLogin, compact = true)
            }
        },
    ) {
        LiveDialogTitle("Entrar no ${a.label}")
        LiveDialogBody("Use o e-mail e a senha da conta.")
        TvTextFieldGate(Modifier.fillMaxWidth().focusRequester(firstFocus), label = "E-mail") { gate ->
            OutlinedTextField(
                value = email, onValueChange = { email = it.trim() }, singleLine = true, label = { Text("E-mail") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next, autoCorrectEnabled = false),
                keyboardActions = next, modifier = gate, colors = colors,
            )
        }
        TvTextFieldGate(Modifier.fillMaxWidth(), label = "Senha") { gate ->
            OutlinedTextField(
                value = pass, onValueChange = { pass = it }, singleLine = true, label = { Text("Senha") },
                visualTransformation = if (showPass) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password, imeAction = if (a == Account.NUVIO && serverOpen) ImeAction.Next else ImeAction.Done, autoCorrectEnabled = false,
                ),
                keyboardActions = if (a == Account.NUVIO && serverOpen) next else done,
                trailingIcon = {
                    IconButton(onClick = { showPass = !showPass }) {
                        Icon(if (showPass) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPass) "Esconder senha" else "Mostrar senha", tint = TextTertiary)
                    }
                },
                modifier = gate, colors = colors,
            )
        }
        if (a == Account.NUVIO) {
            if (serverOpen) TvTextFieldGate(Modifier.fillMaxWidth(), label = "Servidor próprio") { gate ->
                OutlinedTextField(
                    value = server, onValueChange = { server = it.trim() }, singleLine = true,
                    label = { Text("Servidor próprio (vazio usa o oficial)") }, placeholder = { Text("api.nuvio.tv") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = done, modifier = gate, colors = colors,
                )
            } else JoabButton("Usar servidor próprio", { serverOpen = true }, compact = true)
        }
        f.error?.let { Text(it, color = DangerText, fontSize = 14.sp, lineHeight = 20.sp, fontFamily = Inter) }
    }
    if (tv) LaunchedEffect(Unit) { liveFocusSoon(firstFocus) }
}

// ── Passo 2. Trazer tudo, no mesmo diálogo ──────────────────────────────────

private enum class Phase { ASK, RUN, DONE }

/**
 * O diálogo de entrada vira "Conectado" com Trazer tudo em foco, roda as partes no lugar e termina no que veio.
 * [AccountsViewModel.LoginFlow.Step.intent] chega só com Coleções marcada (vindo do cartão de coleções).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportStepDialog(s: AccountsViewModel.LoginFlow.Step, st: AccountsViewModel.Ui, vm: AccountsViewModel, onOpenHome: () -> Unit) {
    val a = s.account
    val parts = a.parts
    val tv = LocalIsTv.current
    var sel by remember { mutableStateOf(parts.associateWith { s.intent == null || it == s.intent }) }
    var choosing by remember { mutableStateOf(s.intent != null) }
    var started by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<Map<ImportPart, PartRecord>?>(null) }
    val scope = rememberCoroutineScope()
    val goFocus = remember { FocusRequester() }
    val retryFocus = remember { FocusRequester() }
    val homeFocus = remember { FocusRequester() }
    val firstLine = remember { FocusRequester() }

    val profiles = st.profiles
    val loading = a == Account.NUVIO && (profiles.state == Load.IDLE || profiles.state == Load.LOADING)
    val phase = if (results != null) Phase.DONE else if (started) Phase.RUN else Phase.ASK
    val chosen = parts.filter { sel[it] == true }
    val run = st.running[a.key]
    LaunchedEffect(Unit) { if (a == Account.NUVIO && profiles.state == Load.FAIL) vm.retryProfiles() }

    fun go(list: List<ImportPart>) {
        if (list.isEmpty()) return
        results = null
        started = true
        scope.launch { results = vm.runImport(a, list).await() }
    }

    val bad = parts.filter { results?.get(it)?.ok == false }
    val body: @Composable ColumnScope.() -> Unit = {
        val email = st.snap(a).email.ifBlank { "Conta conectada" }
        if (phase == Phase.DONE) {
            val res = results.orEmpty()
            LiveDialogTitle("O que veio do ${a.label}")
            LiveDialogBody(
                "Agora mesmo" + (if (a == Account.NUVIO) ", perfil ${st.pick.name} do Nuvio" else "") +
                    ". Foi para o perfil ${st.profileName} deste aparelho.",
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                parts.filter { res[it] != null }.forEach { p -> PartLine(p, res.getValue(p)) }
            }
        } else {
            LiveDialogTitle("${a.label} conectado")
            LiveDialogBody(email)
            if (a == Account.NUVIO && profiles.state == Load.OK && profiles.list.size > 1) {
                Text("De qual perfil do Nuvio?", color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Inter)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.list.forEach { p ->
                        FilterChipPill(
                            p.name, p.index == st.pick.index, { if (phase == Phase.ASK) vm.pickNuvioProfile(p) },
                            modifier = Modifier.focusProperties { down = goFocus },
                            icon = if (p.index == st.pick.index) Icons.Default.Check else null,
                        )
                    }
                }
            }
            if (a == Account.NUVIO && profiles.state == Load.FAIL) {
                Text("Não deu para ver os perfis. Vai do Principal.", color = Gold, fontSize = 14.sp, fontFamily = Inter)
            }
            Text("Vai para o perfil ${st.profileName} deste aparelho", color = TextSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = Inter)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                parts.forEach { p ->
                    val done = run?.done?.get(p)
                    when {
                        phase == Phase.RUN && done != null -> PartLine(p, done)
                        phase == Phase.RUN && run != null && run.current == p && sel[p] == true ->
                            LiveLine(LiveMark.NOW, "Trazendo ${p.what}" + if (run.detail.isNotBlank()) ", ${run.detail}" else "")
                        else -> LiveLine(
                            if (sel[p] == true) LiveMark.OK else LiveMark.OFF, p.line,
                            if (p == parts.first()) Modifier.focusRequester(firstLine) else Modifier,
                            onClick = if (choosing && phase == Phase.ASK) ({ sel = sel + (p to (sel[p] != true)) }) else null,
                        )
                    }
                }
            }
        }
    }
    val actions: @Composable () -> Unit = {
        LiveDialogActions {
            if (phase == Phase.DONE) {
                if (bad.isNotEmpty()) JoabButton(
                    "Tentar o que faltou", {
                        sel = parts.associateWith { it in bad }
                        go(bad)
                    }, Modifier.focusRequester(retryFocus), icon = Icons.Default.Refresh, tone = ButtonTone.PRIMARY, compact = true,
                )
                JoabButton(
                    "Ver minha Início", { vm.closeLogin(); onOpenHome() }, Modifier.focusRequester(homeFocus),
                    icon = Icons.Default.Dashboard, tone = if (bad.isEmpty()) ButtonTone.PRIMARY else ButtonTone.SECONDARY, compact = true,
                )
                JoabButton("Fechar", vm::closeLogin, compact = true)
            } else {
                val label = when (chosen.size) {
                    parts.size -> "Trazer tudo"
                    1 -> "Trazer ${chosen[0].what}"
                    else -> "Trazer ${chosen.size} de ${parts.size}"
                }
                when {
                    phase == Phase.RUN -> JoabButton("Trazendo…", {}, Modifier.focusRequester(goFocus), icon = Icons.Default.Download, tone = ButtonTone.PRIMARY, busy = true, busyLabel = "Trazendo…", compact = true)
                    loading -> JoabButton("Buscando perfis…", {}, Modifier.focusRequester(goFocus), icon = Icons.Default.Download, tone = ButtonTone.PRIMARY, busy = true, busyLabel = "Buscando perfis…", compact = true)
                    chosen.isEmpty() -> JoabButton("Marque o que trazer", {}, Modifier.focusRequester(goFocus), tone = ButtonTone.PRIMARY, enabled = false, compact = true)
                    else -> JoabButton(label, { go(chosen) }, Modifier.focusRequester(goFocus), icon = Icons.Default.Download, tone = ButtonTone.PRIMARY, compact = true)
                }
                if (phase == Phase.ASK && !choosing) JoabButton("Escolher o que trazer", { choosing = true }, icon = Icons.Default.Tune, compact = true)
                if (phase == Phase.RUN) JoabButton("Fechar", vm::closeLogin, compact = true)
                else JoabButton("Agora não", vm::closeLogin, compact = true)
            }
        }
    }
    LiveDialog(onDismiss = vm::closeLogin, width = 720.dp, actions = actions, content = body)
    // Escolher o que trazer some com o botão que estava em foco. O foco vai à primeira linha, que virou alvo.
    if (tv && s.intent == null) LaunchedEffect(choosing) { if (choosing) liveFocusSoon(firstLine) }
    // O foco acompanha a fase. Perguntando e trazendo no Trazer tudo, no fim em Ver minha Início (ou no que faltou).
    if (tv) LaunchedEffect(phase, bad.isEmpty()) { liveFocusSoon(if (phase != Phase.DONE) goFocus else if (bad.isEmpty()) homeFocus else retryFocus) }
}

/** Uma parte trazida, em duas linhas. */
@Composable
private fun PartLine(p: ImportPart, r: PartRecord) {
    val s = partSummary(p, r)
    LiveLine(
        when (s.mark) { Mark.OK -> LiveMark.OK; Mark.BAD -> LiveMark.BAD; Mark.NONE -> LiveMark.NONE },
        s.text, sub = s.sub.ifBlank { null },
    )
}

// ── Painel Mais ─────────────────────────────────────────────────────────────

/** Perfil do Nuvio em lista, cada parte com o que veio dela e Sair da conta. */
@Composable
private fun MorePanel(a: Account, st: AccountsViewModel.Ui, vm: AccountsViewModel, onDismiss: () -> Unit, onWhat: () -> Unit) {
    val tv = LocalIsTv.current
    val snap = st.snap(a)
    val entry = st.log[a]
    val run = st.running[a.key]
    val scope = rememberCoroutineScope()
    var pickProfile by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var pushText by remember { mutableStateOf<String?>(null) }
    var pushing by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }
    val profileList = st.profiles.list
    val showProfile = a == Account.NUVIO && snap.signedIn && st.profiles.state == Load.OK && profileList.size > 1

    LivePanel(a.label, if (snap.signedIn) snap.email.ifBlank { "Conta conectada" } else "Conta", onDismiss) {
        if (!snap.signedIn) {
            LiveItem("Usar e-mail e senha", { onDismiss(); vm.startEmail(a) }, Modifier.focusRequester(first), icon = Icons.Default.Person)
            if (entry != null) LiveItem("O que veio", onWhat, icon = Icons.Default.History)
            return@LivePanel
        }
        var firstSet = false
        fun req(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
        if (showProfile) LiveItem(
            "Perfil do Nuvio", { pickProfile = true }, req(), icon = Icons.Default.Person, sub = "OK mostra os perfis", tag = st.pick.name,
        )
        Text(
            "TRAZER SÓ UMA PARTE", color = Accent, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.6.sp, fontFamily = Inter,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        a.parts.forEach { p ->
            val r = entry?.part(p)
            val busy = run != null && run.parts.size == 1 && run.parts[0] == p
            LiveItem(
                p.label,
                { if (run == null) vm.runAndTell(a, listOf(p)) },
                req(),
                icon = partIcon(p),
                sub = if (busy) "Trazendo…" else when {
                    r == null -> "Ainda não veio"
                    !r.ok -> "Faltou. " + r.why.ifBlank { "Tente de novo." }
                    else -> partSummary(p, r).text + ", " + whenText(r.at)
                },
                busy = busy,
            )
        }
        if (a == Account.STREMIO) LiveItem(
            "Enviar meus addons", {
                if (!pushing) {
                    pushing = true
                    pushText = "Enviando…"
                    scope.launch { pushText = runCatching { vm.pushStremioAddons() }.getOrElse { it.message ?: "Não deu para enviar" }; pushing = false }
                }
            },
            icon = Icons.Default.CloudUpload, busy = pushing,
            sub = pushText ?: "Manda os addons do JOTV para a conta Stremio",
        )
        LiveItem("Sair da conta", { confirmExit = true }, icon = Icons.AutoMirrored.Filled.Logout)
    }
    if (tv) LaunchedEffect(Unit) { liveFocusSoon(first) }

    if (pickProfile) LiveChoice(
        "Perfil do Nuvio", "De onde vem o que é trazido",
        profileList.map { Triple(it.name, null, it.index == st.pick.index) },
        onPick = { i -> vm.pickNuvioProfile(profileList[i]); pickProfile = false },
        onDismiss = { pickProfile = false },
    )
    if (confirmExit) CatalogConfirm(
        title = "Sair do ${a.label}?", body = "O que já veio continua neste aparelho.", confirm = "Sair", danger = true,
        onConfirm = { confirmExit = false; vm.logout(a); onDismiss() },
        onDismiss = { confirmExit = false },
    )
}

private fun partIcon(p: ImportPart) = when (p) {
    ImportPart.ADDONS -> Icons.Default.Tune
    ImportPart.LIBRARY -> Icons.Default.Dashboard
    ImportPart.COLLECTIONS -> Icons.Default.Dashboard
    ImportPart.DEBRID -> Icons.Default.Key
}

// ── Painel O que veio ───────────────────────────────────────────────────────

/** O resumo que antes sumia num aviso. Fica no cartão e aqui. */
@Composable
private fun WhatPanel(a: Account, st: AccountsViewModel.Ui, vm: AccountsViewModel, onDismiss: () -> Unit, onHome: () -> Unit) {
    val tv = LocalIsTv.current
    val e = st.log[a]
    val first = remember { FocusRequester() }
    // Registro sumiu (a conta saiu e o perfil mudou). Fecha fora da composição.
    LaunchedEffect(e == null) { if (e == null) onDismiss() }
    if (e == null) return
    val bad = e.failedParts
    LivePanel(
        "O que veio do ${a.label}",
        whenText(e.at).replaceFirstChar { it.uppercase() } + if (a == Account.NUVIO && e.profile.isNotBlank()) ", perfil ${e.profile}" else "",
        onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            a.parts.filter { e.part(it) != null }.forEach { p -> PartLine(p, e.part(p)!!) }
        }
        var firstSet = false
        fun req(): Modifier = if (!firstSet) { firstSet = true; Modifier.focusRequester(first) } else Modifier
        if (bad.isNotEmpty() && st.snap(a).signedIn) LiveItem(
            "Tentar o que faltou", { onDismiss(); vm.runAndTell(a, bad) }, req(), icon = Icons.Default.Refresh,
        )
        LiveItem("Ver minha Início", onHome, req(), icon = Icons.Default.Dashboard)
    }
    if (tv) LaunchedEffect(Unit) { liveFocusSoon(first) }
}
