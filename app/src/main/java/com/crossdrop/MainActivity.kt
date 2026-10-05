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
        if (intent?.getStringExtra("dev_share") != null) {
            android.os.Handler(mainLooper).postDelayed(
                { container.qrShare.startDebug() }, 1500)
        }
        // -e dev_vault rw|ro|pin — host a vault seeded with test files
        intent?.getStringExtra("dev_vault")?.let { mode ->
            android.os.Handler(mainLooper).postDelayed({
                seedVault(container)
                container.vaultHost.start(
                    writable = mode != "ro", usePin = mode == "pin")
                com.crossdrop.service.TransferService.start(
                    container.contextForService())
            }, 1500)
        }
        // -e dev_vault_mount host[:port] — mount a vault directly (no mDNS on
        // emu); port 0/omitted loops back to our own vault once it's bound.
        intent?.getStringExtra("dev_vault_mount")?.takeIf { it.isNotBlank() }?.let { t ->
            val host = t.substringBefore(':')
            val port = t.substringAfter(':', "0").toIntOrNull() ?: 0
            val pin = intent.getStringExtra("dev_vault_pin")
            android.os.Handler(mainLooper).postDelayed(
                { container.mountVaultAt(host, port, pin) }, 2500)
        }
        // -e dev_vault_peer host[:port] — put a vault beacon on the radar;
        // port 0 resolves to our own hosted vault (self beacons are filtered).
        intent?.getStringExtra("dev_vault_peer")?.takeIf { it.isNotBlank() }?.let { t ->
            val host = t.substringBefore(':')
            val port = t.substringAfter(':', "0").toIntOrNull() ?: 0
            android.os.Handler(mainLooper).postDelayed(
                { container.injectVaultPeer(host, port) }, 2500)
        }
    }

    /** Debug: drop a few files into the vault root so the explorer has content. */
    private fun seedVault(container: com.crossdrop.di.AppContainer) {
        val dir = java.io.File(container.contextForService().filesDir, "vault")
        java.io.File(dir, "docs").mkdirs()
        java.io.File(dir, "docs/welcome.txt").writeText(
            "CrossDrop LAN vault — hello from the host.\n")
        java.io.File(dir, "media").mkdirs()
        java.io.File(dir, "media/big.bin").writeBytes(
            ByteArray(3 * 1024 * 1024) { (it % 251).toByte() })
        java.io.File(dir, "readme.md").writeText("# vault\nServed over LAN.\n")
    }
}
