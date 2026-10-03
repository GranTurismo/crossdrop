package com.crossdrop

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.crossdrop.ui.Root
import com.crossdrop.ui.theme.CrossDropTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as CrossDropApp).container
        handleDevPeer(intent, container)
        setContent {
            CrossDropTheme {
                Root(container)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleDevPeer(intent, (application as CrossDropApp).container)
    }

    /** Dev hook: -e dev_peer 10.0.2.17:44885 fires a self-test at that endpoint. */
    private fun handleDevPeer(intent: android.content.Intent?, container: com.crossdrop.di.AppContainer) {
        if (!BuildConfig.DEBUG) return
        android.util.Log.i("DevPeer", "extras=${intent?.extras?.keySet()?.joinToString()} dev_peer=${intent?.getStringExtra("dev_peer")}")
        intent?.getStringExtra("dev_peer")?.takeIf { it.isNotBlank() }?.let { peer ->
            val host = peer.substringBefore(':')
            val port = peer.substringAfter(':', "0").toIntOrNull() ?: 0
            android.os.Handler(mainLooper).postDelayed(
                { container.injectPeer(null, host, port) }, 1500)
        }
        intent?.getStringExtra("dev_send")?.takeIf { it.isNotBlank() }?.let { peer ->
            val host = peer.substringBefore(':')
            val port = peer.substringAfter(':', "0").toIntOrNull() ?: 0
            android.os.Handler(mainLooper).postDelayed(
                { container.coordinator.selfTransfer(host, port) }, 1500)
        }
    }
}
