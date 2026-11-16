package com.joabtv.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.joabtv.app.ui.common.FilterChipPill
import com.joabtv.app.ui.common.LocalIsTv
import com.joabtv.app.ui.theme.Accent
import com.joabtv.app.ui.theme.BackgroundCard
import com.joabtv.app.ui.theme.DangerText
import com.joabtv.app.ui.theme.Surface2
import com.joabtv.app.ui.theme.TextPrimary
import com.joabtv.app.ui.theme.TextSecondary
import com.joabtv.app.ui.theme.TextTertiary
import kotlinx.coroutines.launch

/**
 * Escolha da foto do perfil. Galeria do aparelho (só no toque, na TV não há galeria), fotos do Nuvio quando a conta
 * está conectada e Sem foto para voltar à inicial. [avatar] é o valor atual (ver ProfilePhotos) e [onChange] recebe o novo.
 * [profileId] vazio é de perfil que ainda não foi criado.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilePhotoChooser(
    avatar: String,
    profileId: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: ProfilesViewModel = hiltViewModel(),
) {
    val tv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    val nuvioOn by vm.nuvioConnected.collectAsStateWithLifecycle()
    var saving by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        saving = true
        failed = false
        scope.launch {
            val v = vm.savePhoto(uri, profileId)
            saving = false
            if (v == null) failed = true else onChange(v)
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Foto", color = TextTertiary, fontSize = 12.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!tv) FilterChipPill(
                "Galeria", false,
                { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                icon = Icons.Default.PhotoLibrary, busy = saving,
            )
            if (nuvioOn) FilterChipPill("Fotos do Nuvio", false, { catalog = true }, icon = Icons.Default.Person)
            if (avatar.isNotBlank()) FilterChipPill("Sem foto", false, { failed = false; onChange("") }, icon = Icons.Default.Close)
        }
        if (failed) Text("Não deu para ler essa imagem. Escolha outra.", color = DangerText, fontSize = 13.sp)
    }
    if (catalog) NuvioPhotoDialog(vm, onPick = { onChange(it); catalog = false }, onDismiss = { catalog = false })
}

/** Fotos do Nuvio em grade. Busca ao abrir e mostra o erro com Tentar de novo. */
@Composable
private fun NuvioPhotoDialog(vm: ProfilesViewModel, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var state by remember { mutableStateOf<Result<List<com.joabtv.app.data.accounts.NuvioAvatar>>?>(null) }
    var tries by remember { mutableIntStateOf(0) }
    LaunchedEffect(tries) {
        state = null
        state = vm.nuvioPhotos()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundCard,
        title = { Text("Fotos do Nuvio", color = TextPrimary, fontWeight = FontWeight.ExtraBold) },
        text = {
            when (val s = state) {
                null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Accent)
                    Text("Buscando as fotos…", color = TextSecondary, fontSize = 15.sp)
                }
                else -> s.fold(
                    onSuccess = { list ->
                        if (list.isEmpty()) Text("O Nuvio não tem fotos para escolher agora.", color = TextSecondary, fontSize = 15.sp)
                        else LazyVerticalGrid(
                            columns = GridCells.Adaptive(76.dp),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(list, key = { it.url }) { a -> PhotoTile(a.name, a.url) { onPick(a.url) } }
                        }
                    },
                    onFailure = { e ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(e.message ?: "Não deu para buscar as fotos", color = DangerText, fontSize = 15.sp)
                            TextButton(onClick = { tries++ }) { Text("Tentar de novo", color = Accent, fontWeight = FontWeight.Bold) }
                        }
                    },
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Fechar", color = TextSecondary) } },
    )
}

@Composable
private fun PhotoTile(name: String, url: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier.size(76.dp).clip(shape).background(Surface2)
            .then(if (focused) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .semantics { contentDescription = name }
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}
