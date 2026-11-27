package com.crossdrop

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.discovery.AnnouncedPeers
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.net.DirectWifiJoin
import com.crossdrop.net.TransferCoordinator
import com.crossdrop.net.TransferServer
import com.crossdrop.platform.SystemWifi
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Real hardware, opt-in: this computer joins a phone's CrossDrop Wi-Fi with the desktop app's own code
 * ([DirectWifiJoin] + [SystemWifi]), waits until the phone answers the announcement, stays a moment, then forgets the
 * network (which on a Mac also switches Wi-Fi off and on, so the Mac goes back to its usual network). **This computer is
 * off its usual Wi-Fi meanwhile**: run it from a script that checks the internet is back, see AGENTS.md.
 *
 *     JAVA_TOOL_OPTIONS=-Dcrossdrop.directWifiProbe=1 ./gradlew :desktop:test --tests 'com.crossdrop.DirectWifiProbe' --rerun -i
 */
class DirectWifiProbe {
    @Test
    fun joinsAPhonesCrossDropWifi() = runBlocking {
        assumeTrue(System.getProperty("crossdrop.directWifiProbe") != null)
        val root = Files.createTempDirectory("dw-probe").toFile()
        System.setProperty("crossdrop.home", File(root, "support").path)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = TransferEngine(SessionIdentity(), { "Mac (probe)" }, "0123456789abcdef", os = "mac")
        val server = TransferServer(scope, engine) { }
        val coordinator = TransferCoordinator(scope, engine, server, InboxStore(File(root, "inbox")), HistoryStore(File(root, "h.json")))
        val announced = AnnouncedPeers().also { coordinator.hub.announced = it }
        coordinator.start(); server.start()
        val address = {
            runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
                    .mapNotNull { it.hostAddress }.firstOrNull(com.crossdrop.core.connect.DirectWifi::onGroupSubnet)
            }.getOrNull() ?: com.crossdrop.net.LocalIp.wifiIpv4()
        }
        val join = DirectWifiJoin(scope, engine, { server.port }, announced, SystemWifi(), address)
        try {
            val started = System.currentTimeMillis()
            join.join()
            var last: DirectWifiJoin.State? = null
            val end = withTimeoutOrNull(70_000) {
                while (true) {
                    val s = join.state.value
                    if (s != last) { println("PROBE ${(System.currentTimeMillis() - started) / 1000.0}s state=$s address=${address()}"); last = s }
                    if (s is DirectWifiJoin.State.Joined && s.hostName != null) break
                    if (s is DirectWifiJoin.State.Failed) break
                    delay(100)
                }
                join.state.value
            }
            println("PROBE result=$end radar=${announced.peers.value.values.map { "${it.name}@${it.endpoint}" }}")
            if (end is DirectWifiJoin.State.Joined) delay(15_000)      // time for the phone's screen to show us
        } finally {
            join.shutdown()
            println("PROBE forgot the network")
            server.stop(); scope.cancel(); root.deleteRecursively()
        }
    }
}
