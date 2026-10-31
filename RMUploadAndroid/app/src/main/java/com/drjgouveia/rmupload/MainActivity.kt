package com.drjgouveia.rmupload

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.runtime.LaunchedEffect

class AppState(context: Context) : ViewModel() {
    val auth = AuthService(context)
    val queue = UploadQueue(context, viewModelScope)
}

class MainActivity : ComponentActivity() {
    private val pendingIntents = mutableListOf<Intent>()
    private var state: AppState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingIntents += intent

        setContent {
            val appState: AppState = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppState(applicationContext) as T
            })
            LaunchedEffect(appState) {
                state = appState
                pendingIntents.forEach { handleShared(it) }
                pendingIntents.clear()
            }
            RMUploadTheme {
                ContentView(appState.auth, appState.queue)
            }
        }
    }

    // Files shared from other apps while we're already running (launchMode=singleTop).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (state == null) pendingIntents += intent else handleShared(intent)
    }

    private fun handleShared(intent: Intent) {
        // Links shared from a browser arrive as text.
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            val url = UploadQueue.webURLInText(text)
            if (url != null) state?.queue?.addWebpage(url) else state?.queue?.lastError = "No web link found in “$text”."
            return
        }
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelableExtra<Uri>(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) state?.queue?.add(contentResolver, uris)
    }
}

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(name: String): T? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(name, T::class.java) else getParcelableExtra(name)

@Suppress("DEPRECATION")
private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(name: String): List<T>? =
    if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(name, T::class.java) else getParcelableArrayListExtra(name)
