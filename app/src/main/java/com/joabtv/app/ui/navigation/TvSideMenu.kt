package com.joabtv.app.ui.navigation

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.joabtv.app.R
import com.joabtv.app.data.local.Profile
import com.joabtv.app.ui.common.ButtonTone
import com.joabtv.app.ui.common.JoabButton
import com.joabtv.app.ui.common.rememberReducedMotion
import com.joabtv.app.ui.theme.*
import com.joabtv.app.ui.tv.r
import com.joabtv.app.ui.tv.rs

/** Item do menu lateral da TV. [avatar] troca o ícone pelo avatar do perfil. */
class TvMenuItem(
    val route: String,
    val label: String,
    val icon: ImageVector?,
    val onClick: () -> Unit,
    val avatar: Profile? = null,
)

val TvMenuClosed: androidx.compose.ui.unit.Dp
    @Composable @ReadOnlyComposable get() = 6.r

/**
 * Menu sobreposto com painel em degradê e nomes que entram com opacidade e deslocamento.
 * A animação não altera a largura do conteúdo. A seta entra sempre pelo item ativo.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvSideMenu(
    main: List<TvMenuItem>,
    bottom: List<TvMenuItem>,
    currentRoute: String?,
    requesters: Map<String, FocusRequester>,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
) {
    val reduced = rememberReducedMotion()
    val progress = animateFloatAsState(if (open) 1f else 0f, com.joabtv.app.ui.common.joabTween(220, reduced), label = "menuReveal")
    val closedWidth = TvMenuClosed
    val openWidth = 22.r
    val panelWidth = 30.r
    val panelSlide = panelWidth - closedWidth
    val labelSlide = 0.6.r
    val active = (main + bottom).firstOrNull { it.route == currentRoute } ?: main.first()
    Box(Modifier.fillMaxHeight().width(if (open) openWidth else closedWidth)) {
        Box(
            Modifier.fillMaxHeight().wrapContentWidth(Alignment.Start, unbounded = true).width(panelWidth)
                .graphicsLayer { translationX = -panelSlide.toPx() * (1f - progress.value) }
                .background(Brush.horizontalGradient(
                    0f to Color(0xFA050A14), .65f to Color(0xF0050A14), 1f to Color.Transparent,
                )),
        )
        Column(
            Modifier.fillMaxHeight().width(if (open) openWidth else closedWidth)
            .onFocusChanged { onOpenChange(it.hasFocus) }
            .focusProperties { enter = { requesters[active.route] ?: FocusRequester.Default } }
            .focusGroup()
            .padding(top = 2.6.r, bottom = 2.r),
        ) {
            Row(Modifier.height(3.6.r).wrapContentWidth(Alignment.Start, unbounded = true).width(openWidth).padding(start = 1.5.r), verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.jotv_logo), "JOTV", Modifier.size(3.r))
                Text(
                    "JOTV", color = TextPrimary, fontSize = 1.7.rs, fontWeight = FontWeight.Bold, fontFamily = DisplayFont,
                    letterSpacing = (-0.068).rs,
                    modifier = Modifier.padding(start = 1.r).graphicsLayer {
                        alpha = progress.value
                        translationX = -labelSlide.toPx() * (1f - progress.value)
                    },
                )
            }
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.56.r, Alignment.CenterVertically)) {
                main.forEach { TvMenuRow(it, it.route == currentRoute, progress, requesters[it.route]) }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.56.r)) {
                bottom.forEach { TvMenuRow(it, it.route == currentRoute, progress, requesters[it.route]) }
            }
        }
    }
}

@Composable
private fun TvMenuRow(item: TvMenuItem, active: Boolean, progress: State<Float>, requester: FocusRequester?) {
    var focused by remember { mutableStateOf(false) }
    val labelSlide = 0.6.r
    val fg = when {
        focused -> TextPrimary
        active -> TextPrimary
        else -> TextSecondary
    }
    Box(
        Modifier
            .padding(horizontal = 1.r)
            .fillMaxWidth()
            .height(3.7.r)
            .clip(RoundedCornerShape(0.55.r))
            .background(if (focused) Color.White.copy(alpha = .12f) else Color.Transparent)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab, onClick = item.onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (active) Box(Modifier.size(0.2.r, 1.5.r).clip(RoundedCornerShape(0.2.r)).background(Accent))
        Row(Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(20.r).padding(start = 1.1.r), verticalAlignment = Alignment.CenterVertically) {
            if (item.avatar != null) {
                com.joabtv.app.ui.settings.ProfileAvatar(item.avatar, 1.8.r)
            } else if (item.icon != null) {
                Icon(item.icon, item.label, tint = if (active && !focused) Accent else fg, modifier = Modifier.size(1.75.r))
            }
            Text(
                item.label, color = fg, fontSize = 1.35.rs, fontWeight = FontWeight.SemiBold, fontFamily = DisplayFont,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 1.3.r, end = 0.8.r).graphicsLayer {
                    alpha = progress.value
                    translationX = -labelSlide.toPx() * (1f - progress.value)
                },
            )
        }
    }
}

/** "Sair do JOTV?" na Início, como o webOS. O foco começa em Ficar. */
@Composable
fun TvExitDialog(onExit: () -> Unit, onStay: () -> Unit) {
    val stay = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { stay.requestFocus() } }
    Dialog(onDismissRequest = onStay) {
        Column(
            Modifier.clip(RoundedCornerShape(20.dp)).background(BackgroundCard).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("Sair do JOTV?", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, fontFamily = Inter)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                JoabButton("Ficar", onStay, Modifier.focusRequester(stay), icon = Icons.Default.Check, tone = ButtonTone.PRIMARY)
                JoabButton("Sair", onExit, tone = ButtonTone.DANGER)
            }
        }
    }
}
