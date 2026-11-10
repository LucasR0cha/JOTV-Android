package com.joabtv.app.ui.navigation

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.joabtv.app.R
import com.joabtv.app.ui.theme.*
import com.joabtv.app.ui.tv.r
import com.joabtv.app.ui.tv.rs
import com.joabtv.app.ui.tv.tvPad

/**
 * Barra do menu Superior, por cima do destaque e sem fundo próprio. Logo e perfil à esquerda,
 * cápsula com os itens no centro e Configurações à direita. Item ativo preenchido como no menu
 * lateral e foco com anel branco. Baixo volta ao conteúdo e os lados nunca saem da barra.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TvTopMenu(
    main: List<TvMenuItem>,
    profile: TvMenuItem,
    settings: TvMenuItem,
    currentRoute: String?,
    requesters: Map<String, FocusRequester>,
    onFocusChange: (Boolean) -> Unit,
    onDown: () -> Unit,
) {
    val active = (main + profile + settings).firstOrNull { it.route == currentRoute } ?: main.first()
    // Vindo do conteúdo, só o item ativo aceita foco. Assim Cima sempre entra pelo ativo e os
    // lados nunca caem na barra. Com a barra focada, todos os itens andam com esquerda e direita.
    var inside by remember { mutableStateOf(false) }
    val focusable: (TvMenuItem) -> Boolean = { inside || it.route == active.route }
    Box(
        Modifier.fillMaxWidth().padding(top = 1.6.r).padding(horizontal = tvPad).height(4.4.r)
            .onFocusChanged { inside = it.hasFocus; onFocusChange(it.hasFocus) }
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown) { onDown(); true } else false
            }
            .focusProperties {
                // Entra pelo item ativo. Esquerda e direita vindas do conteúdo não entram na barra.
                enter = { dir ->
                    if (dir == FocusDirection.Left || dir == FocusDirection.Right) FocusRequester.Cancel
                    else requesters[active.route] ?: FocusRequester.Default
                }
            }
            .focusGroup(),
    ) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.jotv_nav_logo), "JOTV", Modifier.size(3.25.r))
            Spacer(Modifier.width(1.6.r))
            TopRound(profile, profile.route == currentRoute, requesters[profile.route], focusable(profile)) {
                com.joabtv.app.ui.settings.ProfileAvatar(checkNotNull(profile.avatar), 2.4.r)
            }
        }
        Row(
            Modifier.align(Alignment.Center).clip(RoundedCornerShape(50)).background(Color(0xF7191919))
                .border(.06.r, Color.White.copy(alpha = .09f), RoundedCornerShape(50)).padding(.4.r),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            main.forEach { item -> TopTab(item, item.route == currentRoute, requesters[item.route], focusable(item)) }
        }
        Box(Modifier.align(Alignment.CenterEnd)) {
            TopRound(settings, settings.route == currentRoute, requesters[settings.route], focusable(settings)) {
                settings.icon?.let { Icon(it, null, tint = if (settings.route == currentRoute) TextOnLight else TextPrimary, modifier = Modifier.size(1.9.r)) }
            }
        }
    }
}

/** Anel branco afastado do item, igual ao foco dos botões do destaque. */
@Composable
private fun Modifier.topFocus(focused: Boolean, shape: Shape, gap: Dp = .2.r): Modifier =
    border(.18.r, if (focused) Color.White else Color.Transparent, shape).padding(gap)

@Composable
private fun Modifier.topItem(item: TvMenuItem, active: Boolean, requester: FocusRequester?, canFocus: Boolean, onFocused: (Boolean) -> Unit): Modifier =
    then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
        .focusProperties { this.canFocus = canFocus }
        .onFocusChanged { onFocused(it.isFocused) }
        .semantics { contentDescription = item.label; selected = active }
        .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab, onClick = item.onClick)

@Composable
private fun TopTab(item: TvMenuItem, active: Boolean, requester: FocusRequester?, canFocus: Boolean) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(50)
    val fg = when {
        active -> TextOnLight
        focused -> TextPrimary
        else -> TextSecondary
    }
    Row(
        Modifier.topFocus(focused, shape, gap = .14.r).height(3.3.r).clip(shape)
            .background(if (active) Color(0xFFF5F5F5) else if (focused) Color.White.copy(alpha = .12f) else Color.Transparent)
            .topItem(item, active, requester, canFocus) { focused = it }
            .padding(horizontal = 1.25.r),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item.icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(1.6.r)) }
        Text(
            item.label, color = fg, fontSize = 1.2.rs, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont, maxLines = 1,
            modifier = Modifier.padding(start = .65.r).clearAndSetSemantics {},
        )
    }
}

@Composable
private fun TopRound(item: TvMenuItem, active: Boolean, requester: FocusRequester?, canFocus: Boolean, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier.topFocus(focused, CircleShape).size(4.r).clip(CircleShape)
            .background(if (active) Color(0xFFF5F5F5) else Color(0xF7191919))
            .border(.06.r, Color.White.copy(alpha = .09f), CircleShape)
            .topItem(item, active, requester, canFocus) { focused = it },
        contentAlignment = Alignment.Center,
    ) { content() }
}
