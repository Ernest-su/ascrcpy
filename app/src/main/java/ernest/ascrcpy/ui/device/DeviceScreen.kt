package ernest.ascrcpy.ui.device

import ernest.ascrcpy.R
import ernest.ascrcpy.adb.AdbClient
import ernest.ascrcpy.theme.WeChatBrand
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ernest.ascrcpy.adb.AdbConnectionState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DeviceScreen(client: AdbClient, onBack: () -> Unit, onDisconnect: () -> Unit) {
    val connection by client.state.collectAsState()
    val context = LocalContext.current
    val isConnected = connection is AdbConnectionState.Connected
    val scope = rememberCoroutineScope()
    val manager = remember(client, context) { DeviceManager(context.applicationContext, client) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var command by rememberSaveable { mutableStateOf("getprop ro.product.model") }
    var commandOutput by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var files by remember { mutableStateOf<List<RemoteFile>>(emptyList()) }
    var currentPath by rememberSaveable { mutableStateOf("/sdcard") }
    var pathInput by rememberSaveable { mutableStateOf("/sdcard") }
    var loading by remember { mutableStateOf(false) }
    var appsRequested by remember { mutableStateOf(false) }
    var requestedPath by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("") }
    var pendingDownload by remember { mutableStateOf<RemoteFile?>(null) }
    var pendingDelete by remember { mutableStateOf<RemoteFile?>(null) }
    val failed = stringResource(R.string.status_operation_failed)
    val completed = stringResource(R.string.status_command_complete)

    fun runOperation(block: suspend () -> Unit) {
        if (loading || !isConnected) return
        scope.launch {
            loading = true
            try { block() }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { status = "$failed ${error.message ?: error.javaClass.simpleName}" }
            finally { loading = false }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val selected = pendingDownload
        pendingDownload = null
        if (uri != null && selected != null) runOperation {
            status = context.getString(R.string.status_downloading, selected.name)
            manager.download(selected, uri) { remote ->
                scope.launch { status = context.getString(R.string.status_downloading, remote) }
            }
            status = context.getString(R.string.status_download_complete, selected.name)
        }
    }
    fun download(entry: RemoteFile) {
        if (entry.type == "file" || entry.directory) {
            pendingDownload = entry
            folderPicker.launch(null)
        }
    }

    LaunchedEffect(tab, currentPath, isConnected, loading) {
        if (!isConnected || loading) return@LaunchedEffect
        if (tab == 1 && !appsRequested) {
            appsRequested = true
            runOperation { apps = manager.applications() }
        }
        if (tab == 2 && requestedPath != currentPath) {
            requestedPath = currentPath
            files = emptyList()
            runOperation { files = manager.files(currentPath) }
        }
    }

    pendingDelete?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = {
                Text(stringResource(
                    if (entry.directory) R.string.delete_folder_confirm else R.string.delete_file_confirm,
                    entry.path))
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    runOperation {
                        status = context.getString(R.string.status_deleting, entry.name)
                        manager.delete(entry)
                        files = manager.files(currentPath)
                        status = context.getString(R.string.status_delete_complete, entry.name)
                    }
                }, enabled = isConnected && !loading) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.device_actions), style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold)
                Text((connection as? AdbConnectionState.Connected)?.device?.endpoint?.serial.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = onDisconnect) { Text(stringResource(R.string.disconnect)) }
            Spacer(Modifier.width(8.dp))
            Surface(shape = MaterialTheme.shapes.large,
                color = if (isConnected) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.errorContainer) {
                Text(if (isConnected) stringResource(R.string.status_connected_short)
                    else stringResource(R.string.status_disconnected),
                    color = if (isConnected) WeChatBrand else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        PrimaryTabRow(selectedTabIndex = tab) {
            listOf(R.string.tab_commands, R.string.tab_apps, R.string.tab_files).forEachIndexed { index, title ->
                Tab(selected = tab == index, onClick = { tab = index },
                    text = { Text(stringResource(title), maxLines = 1) })
            }
        }
        if (loading) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
        if (status.isNotBlank()) Text(status, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        when (tab) {
            0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.tab_commands), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.command_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("getprop ro.product.model", "id", "pwd").forEach { preset ->
                        TextButton(onClick = { command = preset }) { Text(preset.substringAfterLast(' ')) }
                    }
                }
                OutlinedTextField(command, { command = it }, label = { Text(stringResource(R.string.command_hint)) },
                    modifier = Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = { runOperation {
                    commandOutput = withContext(Dispatchers.IO) { client.shell(command).text() }
                    status = completed
                } }, enabled = isConnected && !loading && command.isNotBlank()) {
                    Text(stringResource(R.string.run_command))
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.command_output), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(commandOutput.ifBlank { stringResource(R.string.no_output) }, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            1 -> LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.app_count, apps.size), Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { runOperation { apps = manager.applications() } }, enabled = !loading) {
                            Text(stringResource(R.string.refresh))
                        }
                    }
                }
                items(apps, key = { it.packageName }) { app ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant) {
                                Text(app.name.take(1).uppercase(), Modifier.padding(12.dp), fontWeight = FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(app.name, fontWeight = FontWeight.SemiBold)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(if (app.system) stringResource(R.string.app_system) else stringResource(R.string.app_user),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
            2 -> Column(Modifier.fillMaxSize()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(stringResource(R.string.remote_storage), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically) {
                            val parts = currentPath.split('/').filter { it.isNotEmpty() }
                            TextButton(onClick = { currentPath = "/"; pathInput = "/" }) { Text("/") }
                            var prefix = ""
                            parts.forEach { part ->
                                prefix += "/$part"
                                val destination = prefix
                                Text("›")
                                TextButton(onClick = { currentPath = destination; pathInput = destination }) {
                                    Text(part)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(pathInput, { pathInput = it },
                                label = { Text(stringResource(R.string.remote_path_hint)) },
                                modifier = Modifier.weight(1f), singleLine = true)
                            TextButton(onClick = { currentPath = DeviceManager.normalize(pathInput.trim()) },
                                enabled = !loading) { Text(stringResource(R.string.go_to_path)) }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.file_count, files.size), Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                currentPath = DeviceManager.parent(currentPath); pathInput = currentPath
                            }, enabled = currentPath != "/") { Text(stringResource(R.string.parent_folder)) }
                            TextButton(onClick = { runOperation { files = manager.files(currentPath) } },
                                enabled = !loading) { Text(stringResource(R.string.refresh)) }
                        }
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                    if (files.isEmpty() && !loading) item {
                        Text(stringResource(R.string.empty_folder), Modifier.padding(20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(files, key = { it.path }) { entry ->
                        Row(Modifier.fillMaxWidth()
                            .clickable(enabled = isConnected && !loading && entry.directory) {
                                currentPath = entry.path; pathInput = entry.path
                            }.padding(horizontal = 8.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            val icon = when (entry.type) { "directory" -> "▣"; "link" -> "↗"; else -> "▤" }
                            Box(Modifier.size(46.dp).clip(MaterialTheme.shapes.medium)
                                .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                                Text(icon, style = MaterialTheme.typography.titleLarge)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.Medium)
                                Text(stringResource(R.string.file_summary, typeLabel(entry),
                                    if (entry.directory) "—" else formatBytes(entry.size), formatTime(entry.modifiedSeconds)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            if (entry.directory || entry.type == "file") {
                                TextButton(onClick = { download(entry) }, enabled = isConnected && !loading) {
                                    Text(stringResource(R.string.download))
                                }
                            }
                            Box {
                                var menuExpanded by remember { mutableStateOf(false) }
                                val moreActions = stringResource(R.string.file_actions)
                                TextButton(onClick = { menuExpanded = true },
                                    enabled = isConnected && !loading,
                                    modifier = Modifier.semantics { contentDescription = moreActions }) {
                                    Text("⋮")
                                }
                                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = {
                                        menuExpanded = false
                                        pendingDelete = entry
                                    })
                                }
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun typeLabel(entry: RemoteFile): String = when (entry.type) {
    "directory" -> stringResource(R.string.file_type_directory)
    "link" -> stringResource(R.string.file_type_link)
    "file" -> stringResource(R.string.file_type_file)
    else -> stringResource(R.string.file_type_other)
}
