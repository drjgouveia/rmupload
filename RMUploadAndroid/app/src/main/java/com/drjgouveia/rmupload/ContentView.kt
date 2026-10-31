package com.drjgouveia.rmupload

import android.annotation.SuppressLint
import android.net.Uri
import android.text.format.Formatter
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

private val Ink = Color(0xFF1F1E1B)
private val Paper = Color(0xFFF1EEE6)

@Composable
fun RMUploadTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Paper, onPrimary = Ink)
    } else {
        lightColorScheme(primary = Ink, onPrimary = Paper, background = Paper, surface = Paper)
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun ContentView(auth: AuthService, queue: UploadQueue) {
    if (auth.isLoggedIn) UploadView(auth, queue) else LoginView(auth)
}

@Composable
private fun LoginView(auth: AuthService) {
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<PendingLogin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Send PDFs and EPUBs to your reMarkable",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Button(
                onClick = { pending = auth.beginLogin() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Log in to reMarkable")
            }
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    pending?.let { login ->
        LoginWebDialog(
            url = login.url,
            onCallback = { callback ->
                pending = null
                scope.launch {
                    busy = true
                    try {
                        auth.completeLogin(callback, login)
                        error = null
                    } catch (e: Exception) {
                        error = e.message
                    } finally {
                        busy = false
                    }
                }
            },
            // User dismissed the sheet.
            onDismiss = { pending = null },
        )
    }
}

/**
 * Hosts the Auth0 login page and catches the redirect to the extension's
 * `chrome-extension://` callback, which no browser app could hand back to us.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginWebDialog(url: String, onCallback: (Uri) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Log in") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cancel") }
                    },
                )
            },
        ) { padding ->
            var webView by remember { mutableStateOf<WebView?>(null) }
            BackHandler { webView?.takeIf { it.canGoBack() }?.goBack() ?: onDismiss() }
            AndroidView(
                modifier = Modifier.padding(padding).fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                if (request.url.scheme != Config.REDIRECT_SCHEME) return false
                                onCallback(request.url)
                                return true
                            }
                        }
                        loadUrl(url)
                        webView = this
                    }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadView(auth: AuthService, queue: UploadQueue) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var menuOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
    var urlPrompt by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        queue.add(context.contentResolver, uris)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("RMUpload") },
                navigationIcon = {
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.AccountCircle, "Account") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            auth.session?.email?.let {
                                DropdownMenuItem(text = { Text(it) }, onClick = {}, enabled = false)
                            }
                            DropdownMenuItem(
                                text = { Text("Clear uploaded") },
                                onClick = { menuOpen = false; queue.clearFinished() },
                            )
                            DropdownMenuItem(
                                text = { Text("Log out", color = MaterialTheme.colorScheme.error) },
                                onClick = { menuOpen = false; auth.logout() },
                            )
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { addMenuOpen = true }) { Icon(Icons.Filled.Add, "Add") }
                        DropdownMenu(expanded = addMenuOpen, onDismissRequest = { addMenuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Files…") },
                                onClick = { addMenuOpen = false; picker.launch(UploadQueue.supportedTypes) },
                            )
                            DropdownMenuItem(
                                text = { Text("Webpage…") },
                                onClick = { addMenuOpen = false; urlPrompt = "" },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            BottomAppBar {
                Button(
                    onClick = { scope.launch { queue.uploadAll(auth) } },
                    enabled = !queue.isUploading && queue.hasPending,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                ) {
                    if (queue.isUploading) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.AutoMirrored.Filled.Send, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Send to reMarkable")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                ListItem(
                    headlineContent = { Text("Convert EPUBs to notebooks") },
                    supportingContent = {
                        Text("PDFs are always uploaded as-is. Converted EPUBs become editable notebooks.")
                    },
                    trailingContent = {
                        Switch(checked = queue.convertEPUBToNotebook, onCheckedChange = { queue.convertEPUBToNotebook = it })
                    },
                )
                ListItem(
                    headlineContent = { Text("Reader mode for webpages") },
                    supportingContent = { Text("Keeps only a webpage's article text and images.") },
                    trailingContent = {
                        Switch(checked = queue.readerMode, onCheckedChange = { queue.readerMode = it })
                    },
                )
                HorizontalDivider()
                Text(
                    "Files",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            if (queue.items.isEmpty()) {
                item {
                    Text(
                        "Add files or webpages here, or use “Share → RMUpload” from any app.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(queue.items, key = { it.id }) { item ->
                ItemRow(item, onRemove = { queue.remove(item) }, enabled = item.status != UploadItem.Status.Uploading)
            }
        }
    }

    urlPrompt?.let { text ->
        AlertDialog(
            onDismissRequest = { urlPrompt = null },
            title = { Text("Send a webpage as PDF") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { urlPrompt = it },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = UploadQueue.webURL(text)
                    if (url != null) queue.addWebpage(url) else queue.lastError = "“$text” is not a valid web address."
                    urlPrompt = null
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { urlPrompt = null }) { Text("Cancel") } },
        )
    }

    queue.lastError?.let { message ->
        AlertDialog(
            onDismissRequest = { queue.lastError = null },
            confirmButton = { TextButton(onClick = { queue.lastError = null }) { Text("OK") } },
            title = { Text("Couldn't add file") },
            text = { Text(message) },
        )
    }
}

@Composable
private fun ItemRow(item: UploadItem, onRemove: () -> Unit, enabled: Boolean) {
    val context = LocalContext.current
    ListItem(
        leadingContent = {
            Text(if (item.isEPUB) "EPUB" else "PDF", style = MaterialTheme.typography.labelMedium)
        },
        headlineContent = { Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(
                    if (item.status == UploadItem.Status.Rendering) "Rendering page…"
                    else Formatter.formatShortFileSize(context, item.data.size.toLong())
                )
                (item.status as? UploadItem.Status.Failed)?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (item.status) {
                    UploadItem.Status.Pending -> {}
                    UploadItem.Status.Rendering, UploadItem.Status.Uploading ->
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    UploadItem.Status.Done -> Icon(Icons.Filled.CheckCircle, "Uploaded", tint = Color(0xFF2E7D32))
                    is UploadItem.Status.Failed -> Icon(Icons.Filled.Warning, "Failed", tint = Color(0xFFEF6C00))
                }
                IconButton(onClick = onRemove, enabled = enabled) { Icon(Icons.Filled.Delete, "Remove") }
            }
        },
    )
}
