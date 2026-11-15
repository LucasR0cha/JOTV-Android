package com.joabtv.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.joabtv.app.ui.theme.BackgroundCard
import com.joabtv.app.ui.theme.TextPrimary
import com.joabtv.app.ui.theme.TextSecondary
import coil.compose.AsyncImage

@Composable
fun ContinueOptionsDialog(
    title: String,
    episode: String,
    imageUrl: String,
    canMark: Boolean,
    onDetails: () -> Unit,
    onMark: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundCard,
        title = { Text(title, color = TextPrimary) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                if (imageUrl.isNotBlank()) AsyncImage(
                    model = imageUrl, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).padding(bottom = 8.dp),
                )
                if (episode.isNotBlank()) Text(episode.replace(':', ' '), color = TextSecondary, modifier = Modifier.padding(bottom = 8.dp))
                @Composable fun Action(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, run: () -> Unit) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = run).padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(icon, contentDescription = null, tint = TextPrimary)
                        Text(label, color = TextPrimary)
                    }
                }
                Action("Ir para detalhes", Icons.Default.Info, onDetails)
                if (canMark) Action("Marcar como assistido", Icons.Default.CheckCircle, onMark)
                Action("Remover de Continuar assistindo", Icons.Default.DeleteOutline, onRemove)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fechar") } },
    )
}
