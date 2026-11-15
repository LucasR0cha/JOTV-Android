package com.joabtv.app.ui.settings

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.joabtv.app.data.backup.BackupPreview
import com.joabtv.app.ui.common.SettingsRow
import com.joabtv.app.ui.common.SettingsSection
import com.joabtv.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupDialog(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val lanInfo by viewModel.lanInfo.collectAsStateWithLifecycle()
    val lanReceivedJson by viewModel.lanReceivedJson.collectAsStateWithLifecycle()
    val busy by viewModel.backupBusy.collectAsStateWithLifecycle()
    val preview by viewModel.backupPreview.collectAsStateWithLifecycle()
    val message by viewModel.backupMessage.collectAsStateWithLifecycle()

    var includeSecrets by remember { mutableStateOf(false) }
    var lanClientMode by remember { mutableStateOf<String?>(null) }
    var lastHost by remember { mutableStateOf("") }
    var pasteDialogOpen by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { viewModel.stopLanBackupServer() }
    }

    LaunchedEffect(message) {
        val m = message ?: return@LaunchedEffect
        Toast.makeText(context, m, Toast.LENGTH_LONG).show()
        viewModel.clearBackupMessage()
    }

    LaunchedEffect(lanReceivedJson) {
        val incoming = lanReceivedJson ?: return@LaunchedEffect
        viewModel.clearLanReceived()
        viewModel.inspectBackupText(incoming)
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = viewModel.exportBackupJson(includeSecrets)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("Não foi possível gravar o arquivo escolhido.")
                }
            }.onSuccess {
                viewModel.notifyBackupMessage("Arquivo de backup exportado.")
            }.onFailure { e ->
                viewModel.notifyBackupMessage(e.message ?: "Erro ao exportar arquivo.")
            }
        }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        com.joabtv.app.data.backup.readBackupText(it)
                    } ?: error("Não foi possível ler o arquivo.")
                }
            }.onSuccess { text ->
                viewModel.inspectBackupText(text)
            }.onFailure { e ->
                viewModel.notifyBackupMessage(e.message ?: "Erro ao ler arquivo.")
            }
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.exportToDownloads(includeSecrets)
        else viewModel.notifyBackupMessage("Para salvar em Downloads, permita acesso ao armazenamento. Você também pode usar o Wi-Fi")
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = BackgroundCard,
            border = BorderStroke(1.dp, Border),
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth(0.92f)
                .heightIn(max = 640.dp),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Backup e restauração", color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                        Text(
                            busy ?: "Sem servidor externo. Tudo fica entre os seus aparelhos.",
                            color = if (busy != null) Accent else TextSecondary,
                            fontSize = 13.sp,
                        )
                    }
                    TextButton(onClick = onDismiss) { Text("Fechar", color = TextSecondary) }
                }

                SettingsRow(
                    icon = if (includeSecrets) Icons.Default.CheckCircle else Icons.Default.Key,
                    title = "Incluir chaves e sessões",
                    sub = if (includeSecrets) "Inclui Trakt, Debrid, Trackers e chave TMDB" else "Somente addons, histórico, lista, plugins e layout",
                    trailing = {
                        Switch(
                            checked = includeSecrets,
                            onCheckedChange = null,
                            colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Accent),
                        )
                    },
                    onClick = { includeSecrets = !includeSecrets },
                )

                SettingsSection("Wi-Fi direto (sem servidor)")

                val serverInfo = lanInfo
                if (serverInfo != null) {
                    val qrBitmap = remember(serverInfo.url) { viewModel.generateQrBitmap(serverInfo.url) }
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BackgroundCard2,
                        border = BorderStroke(1.dp, Accent.copy(alpha = 0.45f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Servidor Wi-Fi ativo. Digite este endereço e PIN na TV ou leia o QR no celular.",
                                color = TextSecondary,
                                fontSize = 13.sp,
                            )
                            Text("${serverInfo.ip}:${serverInfo.port}", color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                            Text("PIN ${serverInfo.pin}", color = Accent, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                            if (qrBitmap != null) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .padding(10.dp),
                                ) {
                                    Image(
                                        bitmap = qrBitmap.asImageBitmap(),
                                        contentDescription = "QR Code do backup",
                                        modifier = Modifier.size(160.dp),
                                    )
                                }
                            }
                            TextButton(onClick = { viewModel.stopLanBackupServer() }) {
                                Text("Fechar servidor Wi-Fi", color = Danger, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                } else {
                    SettingsRow(
                        icon = Icons.Default.Wifi,
                        title = "Abrir servidor Wi-Fi (QR e PIN)",
                        sub = "Mostra endereço, QR Code e PIN para TV, PC ou navegador do celular",
                        busy = busy != null,
                        onClick = { viewModel.startLanBackupServer(includeSecrets) },
                    )
                }

                SettingsRow(
                    icon = Icons.Default.Download,
                    title = "Trazer de outro aparelho pelo Wi-Fi",
                    sub = "Digite o IP e o PIN mostrados no PC ou em outro celular",
                    busy = busy != null,
                    onClick = { lanClientMode = "pull" },
                )

                SettingsRow(
                    icon = Icons.Default.Upload,
                    title = "Enviar para outro aparelho pelo Wi-Fi",
                    sub = "Envia o perfil atual para PC ou celular com PIN",
                    busy = busy != null,
                    onClick = { lanClientMode = "push" },
                )

                SettingsSection("Arquivo local (.json)")

                SettingsRow(
                    icon = Icons.Default.Save,
                    title = "Exportar arquivo (.json)",
                    sub = "Salva em Downloads, Google Drive ou seletor de arquivos",
                    busy = busy != null,
                    onClick = {
                        try {
                            createDocLauncher.launch(viewModel.suggestedBackupFilename())
                        } catch (_: ActivityNotFoundException) {
                            if (android.os.Build.VERSION.SDK_INT <= 28 && androidx.core.content.ContextCompat.checkSelfPermission(context,
                                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            } else viewModel.exportToDownloads(includeSecrets)
                        }
                    },
                )

                SettingsRow(
                    icon = Icons.Default.FolderOpen,
                    title = "Importar arquivo (.json)",
                    sub = "Escolher um arquivo de backup salvo no aparelho",
                    busy = busy != null,
                    onClick = {
                        try {
                            openDocLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                        } catch (_: ActivityNotFoundException) {
                            viewModel.notifyBackupMessage("Use Wi-Fi direto ou Colar backup JSON neste aparelho.")
                        }
                    },
                )

                SettingsRow(
                    icon = Icons.Default.ContentPaste,
                    title = "Colar backup JSON",
                    sub = "Cole o texto JSON caso o sistema não abra o seletor",
                    busy = busy != null,
                    onClick = { pasteDialogOpen = true },
                )
            }
        }
    }

    lanClientMode?.let { mode ->
        LanClientPromptDialog(
            isPull = mode == "pull",
            initialHost = lastHost,
            onDismiss = { lanClientMode = null },
            onConfirm = { host, pin ->
                lastHost = host
                lanClientMode = null
                if (mode == "pull") viewModel.pullLanBackup(host, pin)
                else viewModel.pushLanBackup(host, pin, includeSecrets)
            },
        )
    }

    if (pasteDialogOpen) {
        PasteJsonDialog(
            onDismiss = { pasteDialogOpen = false },
            onConfirm = { text ->
                pasteDialogOpen = false
                viewModel.inspectBackupText(text)
            },
        )
    }

    preview?.let { p ->
        ConfirmRestoreDialog(
            preview = p,
            onDismiss = { viewModel.clearBackupPreview() },
            onConfirm = { viewModel.confirmRestore(restoreSecrets = true) },
        )
    }
}

@Composable
private fun LanClientPromptDialog(
    isPull: Boolean,
    initialHost: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit,
) {
    var host by remember { mutableStateOf(initialHost) }
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundCard,
        title = {
            Text(
                if (isPull) "Trazer backup pelo Wi-Fi" else "Enviar backup pelo Wi-Fi",
                color = TextPrimary,
                fontWeight = FontWeight.ExtraBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Digite o IP com porta e o PIN de 4 dígitos exibidos no outro aparelho.", color = TextSecondary, fontSize = 13.sp)
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP e porta (ex. 192.168.1.50:48123)") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = pin,
                    onValueChange = { raw -> pin = raw.filter { it.isDigit() }.take(4) },
                    label = { Text("PIN de 4 dígitos") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(host.trim(), pin.trim()) },
                enabled = host.contains(":") && pin.length == 4,
            ) {
                Text(if (isPull) "Buscar" else "Enviar", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        },
    )
}

@Composable
private fun PasteJsonDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundCard,
        title = { Text("Colar backup JSON", color = TextPrimary, fontWeight = FontWeight.ExtraBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Cole o conteúdo completo do arquivo .json de backup.", color = TextSecondary, fontSize = 13.sp)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    maxLines = 6,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("Ler backup", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) } },
    )
}

@Composable
private fun ConfirmRestoreDialog(
    preview: BackupPreview,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val summary = buildString {
        append("Perfil ${preview.profileName}. Contém ${preview.addons} addons, ${preview.history} em progresso, ${preview.watchlist} na lista, ${preview.watchedSeries} séries vistas e ${preview.collections} coleções")
        if (preview.pluginRepos > 0) append(", ${preview.pluginRepos} repos de plugin")
        append(".")
        if (preview.hasSecrets) append(" Também inclui chaves e sessões salvas.")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BackgroundCard,
        title = { Text("Restaurar este backup?", color = TextPrimary, fontWeight = FontWeight.ExtraBold) },
        text = { Text(summary, color = TextSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Restaurar", color = Accent, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar", color = TextSecondary) }
        },
    )
}
