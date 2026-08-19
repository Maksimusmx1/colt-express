package com.coltexpress.client.ui

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.coltexpress.client.protocol.BoardState
import com.coltexpress.client.protocol.Player
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class TrainState(
    val cars: List<CarState>,
    val sheriffCar: Int,
    val players: List<PlayerState>,
)

@Serializable
private data class CarState(
    val index: Int,
    val inside: List<String>,
    val roof: List<String>,
    val lootInside: List<String>,
    val lootRoof: List<String>,
)

@Serializable
private data class PlayerState(
    val id: String,
    val nickname: String,
    val character: String,
)

private val stateJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Train3dPanel(
    board: BoardState?,
    players: List<Player>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 260.dp,
) {
    val webViewRef = remember { mutableWebViewHolder() }
    val lastStateRef = remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Box(modifier = modifier.fillMaxWidth().height(height)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                webViewRef.value = WebView(ctx).apply {
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        allowFileAccess = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mediaPlaybackRequiresUserGesture = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        builtInZoomControls = false
                        displayZoomControls = false
                    }
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            val js = lastStateRef.value ?: return
                            view?.evaluateJavascript("window.setState($js)", null)
                        }
                    }
                    setBackgroundColor(0xFF0a0a0a.toInt())
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    loadUrl("http://212.113.99.178:8080/train3d/")
                }
                webViewRef.value!!
            },
            update = { wv ->
                val state = board?.toState(players)
                if (state != null) {
                    val json = stateJson.encodeToString(TrainState.serializer(), state)
                    val escaped = json.replace("\\", "\\\\").replace("'", "\\'")
                    val jsArg = "'$escaped'"
                    lastStateRef.value = jsArg
                    wv.evaluateJavascript("window.setState($jsArg)", null)
                }
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            webViewRef.value?.destroy()
            webViewRef.value = null
        }
    }
}

private fun BoardState.toState(players: List<Player>): TrainState {
    val mappedPlayers = players.map { p ->
        PlayerState(id = p.id, nickname = p.nickname, character = p.character)
    }
    return TrainState(
        cars = cars.map { c ->
            CarState(
                index = c.index,
                inside = c.inside,
                roof = c.roof,
                lootInside = c.lootInside,
                lootRoof = c.lootRoof,
            )
        },
        sheriffCar = sheriffCar,
        players = mappedPlayers,
    )
}

// Mutable holder to keep WebView across recompositions.
private fun mutableWebViewHolder(): androidx.compose.runtime.MutableState<WebView?> =
    androidx.compose.runtime.mutableStateOf(null)
