package com.joabtv.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.joabtv.app.ui.common.ButtonTone
import com.joabtv.app.ui.common.JoabButton
import com.joabtv.app.ui.common.SpinningIcon
import com.joabtv.app.ui.common.pressScale
import com.joabtv.app.ui.theme.*

// Peças de tela da seção "Seu catálogo", dos diálogos e dos painéis das contas. Todas seguem o
// desenho do webOS (.cat-card, .imp-line, .side-panel) e valem para TV e celular.

/** Largura do grupo de botões nos cartões largos. O primário começa na mesma coluna em todos eles. */
private val ActionsWidth = 520.dp

/** Largura mínima do cartão para os botões irem à direita do texto (sobram uns 300 dp para ele). Abaixo disso eles descem. */
private val WideCard = 920.dp

/**
 * Alvo do menu lateral da TV, entregue pela navegação. O cartão tem os botões à direita do texto, e a busca de foco
 * por geometria acharia blocos de outras seções (as pílulas de Reprodução) à esquerda antes do menu. Com isto a seta
 * esquerda no primeiro botão vai direto ao menu, como nas outras telas. Sem menu na rota, fica null.
 */
internal val LocalMenuFocus = androidx.compose.runtime.staticCompositionLocalOf<FocusRequester?> { null }

private fun iconFor(id: String, label: String): ImageVector = when (id) {
    "login" -> Icons.Default.Key
    "email", "profiles" -> Icons.Default.Person
    "all", "pull" -> if (label == "Tentar de novo" || label.startsWith("Atualizar")) Icons.Default.Refresh else Icons.Default.Download
    "retry" -> Icons.Default.Refresh
    "what" -> Icons.Default.History
    "more", "manage" -> Icons.Default.Tune
    "organize", "rows" -> Icons.Default.Dashboard
    "hero" -> Icons.Default.Movie
    "explore" -> Icons.Default.Extension
    "link" -> Icons.Default.Link
    "open" -> Icons.Default.ViewModule
    else -> Icons.Default.Check
}

private fun cardIcon(key: String): ImageVector = when (key) {
    "addons" -> Icons.Default.Extension
    "cols" -> Icons.Default.ViewModule
    else -> Icons.Default.Dashboard
}

/**
 * Cartão vivo (.cat-card). O cartão não recebe foco. Os alvos são os botões dele, num grupo que começa na
 * mesma coluna em todos os cartões, e o primeiro é o primário. Toque no corpo faz o primário.
 * Cima e Baixo vão do primário de um cartão ao do vizinho ([upTo] e [downTo]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveCard(
    view: CardView,
    requesterOf: (String) -> FocusRequester,
    primaryRequester: FocusRequester,
    onAct: (String) -> Unit,
    modifier: Modifier = Modifier,
    upTo: FocusRequester? = null,
    downTo: FocusRequester? = null,
    onFocused: (String) -> Unit = {},
) {
    var hasFocus by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val tv = com.joabtv.app.ui.common.LocalIsTv.current
    val menu = LocalMenuFocus.current
    val primary = view.acts.firstOrNull()
    val canTap = primary != null && !primary.busy && !primary.blocked
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (hasFocus) AccentSoft else BackgroundCard2)
            .onFocusChanged { hasFocus = it.hasFocus }
            // O cartão em si não pega foco (só os botões). Vem antes do clickable, que é quem cria o alvo.
            .focusProperties { canFocus = false }
            .pressScale(src, 0.99f)
            .clickable(src, null, enabled = canTap, role = Role.Button) { primary?.let { onAct(it.id) } },
    ) {
        val wide = maxWidth >= WideCard
        val buttons: @Composable () -> Unit = {
            view.acts.forEachIndexed { i, a ->
                // A identidade vem da posição e não do id, então o botão que muda de rótulo (Trazer tudo, Atualizar) segue com o foco.
                key(i) {
                    var m = Modifier.focusRequester(requesterOf(a.id))
                    if (i == 0) m = m.focusRequester(primaryRequester).widthIn(min = 150.dp)
                    m = m.focusProperties {
                        upTo?.let { up = it }
                        downTo?.let { down = it }
                        // Esquerda no primário abre o menu lateral. Sem menu na tela, não anda.
                        if (i == 0 && tv) left = menu ?: FocusRequester.Cancel
                    }.onFocusChanged { if (it.isFocused) onFocused(a.id) }
                    JoabButton(
                        a.label, { onAct(a.id) }, m, icon = iconFor(a.id, a.label),
                        tone = if (i == 0) ButtonTone.PRIMARY else ButtonTone.SECONDARY,
                        busy = a.busy, busyLabel = a.label, enabled = !a.blocked, compact = true,
                    )
                }
            }
        }
        if (wide) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                CardMark(view)
                CardText(view, Modifier.weight(1f).padding(horizontal = 14.dp))
                // Em FlowRow, com fonte grande o último botão desce em vez de ser espremido. O primário fica no mesmo lugar.
                FlowRow(Modifier.width(ActionsWidth), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
        } else {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    CardMark(view)
                    CardText(view, Modifier.weight(1f).padding(start = 14.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
        }
    }
}

@Composable
private fun CardMark(view: CardView) {
    Box(Modifier.size(42.dp).clip(RoundedCornerShape(11.dp)).background(Surface2), contentAlignment = Alignment.Center) {
        if (view.mark != null) Text(view.mark, color = Accent, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Inter)
        else Icon(cardIcon(view.key), null, tint = Accent, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun CardText(view: CardView, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(view.title, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Bold, fontFamily = Inter, modifier = Modifier.weight(1f, fill = false))
            view.tag?.let { LiveTag(it) }
        }
        view.lines.forEach {
            Text(it, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = Inter, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** Etiqueta de estado do cartão (.cat-tag). Verde conectado, âmbar faltou parte, vermelho não veio. */
@Composable
internal fun LiveTag(tag: CardTag) {
    val (fg, bg) = when (tag.tone) {
        TagTone.ON -> Ok to Ok.copy(alpha = .14f)
        TagTone.WARN -> Gold to Gold.copy(alpha = .14f)
        TagTone.BAD -> DangerText to Danger.copy(alpha = .16f)
    }
    Text(
        tag.text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Inter, maxLines = 1,
        modifier = Modifier.padding(start = 10.dp).clip(CircleShape).background(bg).padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

// ── Diálogos e painéis ──────────────────────────────────────────────────────

/**
 * Diálogo centrado no padrão do webOS (.dialog). A janela abraça o conteúdo, então tocar fora fecha.
 * O [content] rola quando a tela é baixa (celular deitado, fonte grande) e os botões de [actions] ficam
 * fixos embaixo, sempre à vista.
 */
@Composable
internal fun LiveDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 560.dp,
    actions: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .widthIn(max = width)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(BackgroundCard)
                .padding(22.dp),
        ) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            if (actions != null) {
                Spacer(Modifier.height(14.dp))
                actions()
            }
        }
    }
}

@Composable
internal fun LiveDialogTitle(text: String) {
    Text(text, color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Inter)
}

@Composable
internal fun LiveDialogBody(text: String, color: Color = TextSecondary) {
    Text(text, color = color, fontSize = 15.sp, lineHeight = 21.sp, fontFamily = Inter)
}

/** Linha de botões de um diálogo. No celular estreito eles quebram em duas linhas. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LiveDialogActions(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

/**
 * Painel que entra pela direita. É o mesmo da Minha Início ([SidePanelDialog]), então os painéis do catálogo
 * têm o mesmo jeito. Rola quando falta altura (celular deitado, fonte grande).
 */
@Composable
internal fun LivePanel(
    title: String,
    sub: String?,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    SidePanelDialog(title, onDismiss, sub = sub) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

/**
 * Item de painel com o mesmo foco do [PanelItem] da Minha Início (fundo suave e borda branca). A diferença é o
 * [busy], que gira o ícone e trava o segundo toque, o que as chamadas de rede das contas pedem.
 */
@Composable
internal fun LiveItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    sub: String? = null,
    tag: String? = null,
    selected: Boolean = false,
    busy: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (focused) AccentSoft else BackgroundCard2)
            .then(if (focused) Modifier.border(2.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .pressScale(src, 0.98f)
            // Ocupado segue focável (na TV o foco não pode pular), só não dispara de novo.
            .clickable(src, null, role = Role.Button) { if (!busy) onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (busy) SpinningIcon(tint = TextPrimary, size = 22.dp)
        else if (icon != null) Icon(icon, null, tint = TextPrimary, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Inter, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!sub.isNullOrBlank()) Text(sub, color = TextSecondary, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = Inter, modifier = Modifier.padding(top = 1.dp))
        }
        if (!tag.isNullOrBlank()) Text(tag, color = TextTertiary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Inter, maxLines = 1)
        if (selected) Icon(Icons.Default.Check, "Em uso", tint = Accent, modifier = Modifier.size(22.dp))
    }
}

/**
 * Uma escolha entre várias, em painel. Usada no perfil do Nuvio e no destaque da Início. O foco já nasce na opção em uso.
 * A lista é preguiçosa, porque um addon grande pode trazer mais de mil fileiras para escolher.
 */
@Composable
internal fun LiveChoice(
    title: String,
    sub: String?,
    choices: List<Triple<String, String?, Boolean>>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val tv = com.joabtv.app.ui.common.LocalIsTv.current
    val start = choices.indexOfFirst { it.third }.coerceAtLeast(0)
    val first = remember { FocusRequester() }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    SidePanelDialog(title, onDismiss, sub = sub) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(choices) { i, (label, hint, selected) ->
                LiveItem(
                    label, { onPick(i) }, if (i == start) Modifier.focusRequester(first) else Modifier,
                    sub = hint, selected = selected,
                )
            }
        }
    }
    if (tv) androidx.compose.runtime.LaunchedEffect(Unit) { liveFocusSoon(first) }
}

/** Foco no controle assim que o alvo existir. Tenta por uns segundos, porque a janela do diálogo demora a montar. */
internal suspend fun liveFocusSoon(r: FocusRequester, tries: Int = 40) {
    repeat(tries) {
        if (runCatching { r.requestFocus() }.isSuccess) return
        kotlinx.coroutines.delay(50)
    }
}

/** Marca de uma linha de importação. */
internal enum class LiveMark { OK, BAD, NONE, NOW, OFF }

/**
 * Linha do que veio ou do que vai vir (.imp-line). Com [onClick] vira alvo de foco marcável (Escolher o que trazer).
 */
@Composable
internal fun LiveLine(
    mark: LiveMark,
    label: String,
    modifier: Modifier = Modifier,
    sub: String? = null,
    onClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    val tint = when (mark) {
        LiveMark.OK -> Ok
        LiveMark.BAD -> Gold
        LiveMark.NONE, LiveMark.OFF, LiveMark.NOW -> TextTertiary
    }
    val fgMark = if (focused) TextOnLight else tint
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .then(if (onClick != null) Modifier.background(if (focused) Color.White else Color.Transparent).onFocusChanged { focused = it.isFocused } else Modifier)
            .then(if (onClick != null) Modifier.clickable(role = Role.Checkbox, onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            when (mark) {
                LiveMark.NOW -> SpinningIcon(tint = Accent, size = 20.dp)
                LiveMark.OK -> Icon(Icons.Default.Check, null, tint = fgMark, modifier = Modifier.size(22.dp))
                LiveMark.BAD -> Icon(Icons.Default.Warning, null, tint = fgMark, modifier = Modifier.size(22.dp))
                LiveMark.NONE -> Icon(Icons.Default.Info, null, tint = fgMark, modifier = Modifier.size(22.dp))
                LiveMark.OFF -> Box(Modifier.size(16.dp).border(2.dp, fgMark, CircleShape))
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                label, color = if (focused) TextOnLight else if (mark == LiveMark.OFF) TextTertiary else TextPrimary,
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter, lineHeight = 22.sp,
            )
            if (!sub.isNullOrBlank()) Text(sub, color = if (focused) TextOnLight.copy(alpha = .72f) else TextTertiary, fontSize = 13.sp, lineHeight = 18.sp, fontFamily = Inter)
        }
    }
}
