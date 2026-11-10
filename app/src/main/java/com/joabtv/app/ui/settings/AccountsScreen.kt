package com.joabtv.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joabtv.app.data.accounts.Account
import com.joabtv.app.ui.common.LocalIsTv
import com.joabtv.app.ui.common.topInsetsPadding
import com.joabtv.app.ui.theme.*

/**
 * Contas Stremio e Nuvio numa tela só delas. Os mesmos cartões das Configurações, com o mesmo Trazer tudo,
 * Mais e O que veio. Quem chega aqui de outra tela (como o convite da Minha Início) já vê o botão da vez.
 */
@Composable
fun AccountsScreen(onBack: () -> Unit, onOpenHome: () -> Unit = onBack, viewModel: AccountsViewModel = hiltViewModel()) {
    val st by viewModel.ui.collectAsStateWithLifecycle()
    val pad = com.joabtv.app.ui.common.pagePadding()
    val tv = LocalIsTv.current
    var panel by remember { mutableStateOf<AccountPanel?>(null) }
    val cards = Account.entries.map { a -> accountView(a, st.snap(a), st.log[a], st.running[a.key]) }
    val reqs = remember { HashMap<String, FocusRequester>() }
    fun req(card: String, act: String) = reqs.getOrPut("$card/$act") { FocusRequester() }

    // Foco inicial na TV. Conta conectada sem nada trazido leva ao Trazer tudo, senão ao primeiro botão.
    LaunchedEffect(Unit) {
        if (tv) {
            val key = firstFocusKey(cards)
            liveFocusSoon(req(key.substringBefore('/'), "primary"))
        }
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
            com.joabtv.app.ui.common.PageHeader("Contas", Modifier.weight(1f), sub = "Stremio e Nuvio no perfil ${st.profileName}")
        }

        val summary: @Composable () -> Unit = {
            com.joabtv.app.ui.common.SummaryCard {
                Text("Suas contas", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))
                Account.entries.forEach { a ->
                    val (text, tone) = accountShort(a, st.snap(a), st.log[a], st.running[a.key])
                    com.joabtv.app.ui.common.SummaryRow(
                        a.label, text,
                        when (tone) { TagTone.ON -> Ok; TagTone.WARN -> Gold; TagTone.BAD -> DangerText; null -> TextPrimary },
                    )
                }
                com.joabtv.app.ui.common.SummaryRow("Vai para o perfil", st.profileName)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Trazer só soma. Nada do que já está no JOTV é apagado, e o progresso daqui vale quando é mais novo.",
                    color = TextTertiary, fontSize = 13.sp, lineHeight = 18.sp,
                )
            }
        }

        com.joabtv.app.ui.common.ContentWithSummary(summary) { below ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = pad).padding(top = 6.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                cards.forEachIndexed { i, c ->
                    LiveCard(
                        c, requesterOf = { req(c.key, it) }, primaryRequester = req(c.key, "primary"),
                        onAct = { accountAct(viewModel, Account.of(c.key)!!, it, { p -> panel = p }) },
                        upTo = cards.getOrNull(i - 1)?.let { req(it.key, "primary") },
                        downTo = cards.getOrNull(i + 1)?.let { req(it.key, "primary") },
                    )
                }
                below?.let { Spacer(Modifier.height(8.dp)); it() }
            }
        }
    }

    AccountOverlays(viewModel, panel, { panel = it }, onOpenHome = { viewModel.openHomeOnNew(); onOpenHome() })
}
