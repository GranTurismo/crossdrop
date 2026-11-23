package com.crossdrop

import com.crossdrop.core.Endpoint
import com.crossdrop.core.connect.WifiQr
import com.crossdrop.net.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files

/**
 * By hand only: the Mac plays the phone that showed the code, an Android emulator plays the one that
 * scanned it. Skipped unless the test JVM gets `-Dcrossdrop.linkProbe=1`.
 *
 *     JAVA_TOOL_OPTIONS=-Dcrossdrop.linkProbe=1 ./gradlew :desktop:test --tests '*LinkInteropProbe' --rerun -i
 *
 * It prints a code to hand the emulator (`-e dev_join_skip_wifi '<code>'`), waits for the emulator's
 * ANNOUNCE, forwards its port with adb (the emulator's address isn't reachable from the Mac otherwise) and
 * sends it a file, which someone accepts on the emulator.
 */
class LinkInteropProbe {
    /**
     * Shake to connect against an emulator (`-Dcrossdrop.shakeProbe=1`): the Mac listens on :47000 and plays a
     * phone shaken at the same moment — when the emulator's SHAKE arrives it shakes back at once (through an adb
     * forward), so both sides should match. Launch the emulator with `-e dev_peer 10.0.2.2:47000 -e dev_shake 1`.
     */
    @Test fun macShakesBack() = runBlocking {
        assumeTrue(System.getProperty("crossdrop.shakeProbe") == "1")
        val home = Files.createTempDirectory("crossdrop-shake-probe").toFile()
        System.setProperty("crossdrop.home", File(home, "support").path)
        // The emulator knows the Mac only as its hand-added peer (`-e dev_peer`), whose radar id is this; real
        // discovery uses the device's own id, so taking it here is what makes the match land on a radar node.
        val mac = LinkAndBackupTest.Node("Mac shaker", File(home, "mac"), instanceId = "manual-10.0.2.2:47000")
        val listener = ServerSocket(47_000)
        mac.server.stop()
        mac.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) { val s = runCatching { listener.accept() }.getOrNull() ?: break; mac.coordinator.hub.onInboundSocket(s) }
        }
        println("PROBE shaker listening on 10.0.2.2:47000")
        val emu = withTimeoutOrNull(180_000) {
            var p = mac.announced.peers.value.values.firstOrNull()
            while (p == null) { delay(50); p = mac.announced.peers.value.values.firstOrNull() }
            p
        } ?: error("no SHAKE within 3 minutes")
        val port = emu.endpoint!!.port
        println("PROBE shake from ${emu.name} (port $port): shaking back")
        ProcessBuilder("adb", "forward", "tcp:$port", "tcp:$port").start().waitFor()
        mac.coordinator.hub.shake(listOf(emu.copy(endpoint = Endpoint("127.0.0.1", port))), 47_000)
        println("PROBE match on the Mac: ${mac.coordinator.hub.shakeMatch.value}")
        delay(8_000)
        ProcessBuilder("adb", "forward", "--remove", "tcp:$port").start().waitFor()
        listener.close(); mac.close()
        home.deleteRecursively()
        Unit
    }

    /**
     * Clipboard sharing against an emulator (`-Dcrossdrop.clipProbe=1`), with this Mac's **real** clipboard: listens on
     * :47000, agrees to the first pairing request and turns clipboard sharing on for it, finds the emulator's listener
     * through adb (logcat "listening on") and forwards it, then for `crossdrop.probeMinutes` shares what is copied on
     * the Mac and puts on the Mac's clipboard what the emulator shares. Launch the emulator with `-e dev_peer 10.0.2.2:47000`,
     * add the Mac in Settings → My devices and turn "Share clipboard" on there.
     */
    @Test fun macSharesClipboard() = runBlocking {
        assumeTrue(System.getProperty("crossdrop.clipProbe") == "1")
        val home = Files.createTempDirectory("crossdrop-clip-probe").toFile()
        System.setProperty("crossdrop.home", File(home, "support").path)
        val mac = LinkAndBackupTest.Node("Mac clipboard", File(home, "mac"), instanceId = "manual-10.0.2.2:47000")
        val pairs = com.crossdrop.core.pairing.PairingStore(File(mac.root, "pairings.json")).also { mac.coordinator.hub.pairings = it }
        val listener = ServerSocket(47_000)
        mac.server.stop()
        mac.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) { val s = runCatching { listener.accept() }.getOrNull() ?: break; mac.coordinator.hub.onInboundSocket(s) }
        }
        mac.scope.launch {
            mac.coordinator.hub.pairPrompt.collect { p ->
                if (p != null) { println("PROBE pair request from ${p.peerName}, code ${p.sas}: agreeing"); mac.coordinator.hub.respondToPair(p, true) }
            }
        }
        mac.scope.launch { pairs.devices.collect { ds -> ds.filter { !it.clipboard }.forEach { println("PROBE paired with ${it.name}: sharing on"); pairs.setClipboard(it.id, true) } } }
        val radar = kotlinx.coroutines.flow.MutableStateFlow<Map<String, com.crossdrop.core.Peer>>(emptyMap())
        val presence = com.crossdrop.net.PairedPresence(mac.scope, mac.engine, pairs, radar, retryMs = 2_000)
        lateinit var watcher: com.crossdrop.platform.ClipboardWatcher
        val sync = com.crossdrop.net.ClipboardSync(mac.scope, mac.engine, pairs, presence.present, apply = {
            println("PROBE from the phone: \"${it.take(80)}\" -> Mac clipboard"); watcher.write(it)
        }).also { mac.coordinator.hub.clipboardReceiver = it }
        watcher = com.crossdrop.platform.ClipboardWatcher(com.crossdrop.platform.SystemClipboard, sync.active, onCopy = {
            println("PROBE Mac copied \"${it.take(80)}\": ${sync.copied(it)}")
        })
        watcher.start(mac.scope)
        println("PROBE clipboard Mac listening on 10.0.2.2:47000")
        val minutes = System.getProperty("crossdrop.probeMinutes")?.toIntOrNull() ?: 6
        // The test JVM doesn't get the shell's PATH (it is forked by the Gradle daemon), and another device attached
        // makes a bare `adb shell` ambiguous: `-Dcrossdrop.adb=<path>`, `-Dcrossdrop.adbSerial=emulator-5554`.
        val adb = listOf(System.getProperty("crossdrop.adb") ?: "${System.getProperty("user.home")}/Library/Android/sdk/platform-tools/adb") +
            (System.getProperty("crossdrop.adbSerial")?.let { listOf("-s", it) } ?: emptyList())
        var forwarded = 0
        repeat(minutes * 12) {
            // The emulator's listener isn't reachable from the Mac: find its port and forward it (again after a restart).
            val log = runCatching {
                ProcessBuilder(adb + listOf("shell", "logcat -d | grep 'listening on :'")).start().inputStream.bufferedReader().readText()
            }.getOrDefault("")
            val port = Regex("listening on :(\\d+)").findAll(log).lastOrNull()?.groupValues?.get(1)?.toInt() ?: 0
            if (port != 0 && port != forwarded) {
                ProcessBuilder(adb + listOf("forward", "tcp:$port", "tcp:$port")).start().waitFor()
                forwarded = port
                radar.value = mapOf("emulator" to com.crossdrop.core.Peer("emulator", "Emulator", Endpoint("127.0.0.1", port)))
                println("PROBE emulator listener :$port forwarded")
            }
            delay(5_000)
            println("PROBE present=${presence.present.value.keys.map { pairs.get(it)?.name }} status=${sync.status.value.mapKeys { pairs.get(it.key)?.name }}")
        }
        if (forwarded != 0) ProcessBuilder(adb + listOf("forward", "--remove", "tcp:$forwarded")).start().waitFor()
        listener.close(); mac.close()
        home.deleteRecursively()
        Unit
    }

    /**
     * The Mac as a paired backup computer for an emulator (`-Dcrossdrop.backupProbe=1`): listens on :47000,
     * agrees to the first pairing request, files what arrives, answers re-checks, and prints what it holds
     * every 10 s for [minutes]. The emulator reaches it at 10.0.2.2:47000 (`-e dev_peer 10.0.2.2:47000`).
     */
    @Test fun macKeepsBackups() = runBlocking {
        assumeTrue(System.getProperty("crossdrop.backupProbe") == "1")
        val home = Files.createTempDirectory("crossdrop-backup-probe").toFile()
        System.setProperty("crossdrop.home", File(home, "support").path)
        val mac = LinkAndBackupTest.Node("Mac backup", File(home, "mac"))
        val pairs = com.crossdrop.core.pairing.PairingStore(File(home, "pairings.json")).also { mac.coordinator.hub.pairings = it }
        val folders = java.util.concurrent.ConcurrentHashMap<String, String>()
        val rx = com.crossdrop.backup.DesktopBackupReceiver(
            pairs, mac.history, root = { File(home, "Backups") }, stateDir = File(home, "state"),
            folderNames = object : com.crossdrop.backup.DesktopBackupReceiver.FolderNames {
                override fun get(pairId: String) = folders[pairId]
                override fun put(pairId: String, name: String) { folders[pairId] = name }
            },
        )
        mac.coordinator.hub.backupReceiver = rx
        val listener = ServerSocket(47_000)
        mac.server.stop()
        mac.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) { val s = runCatching { listener.accept() }.getOrNull() ?: break; mac.coordinator.hub.onInboundSocket(s) }
        }
        mac.scope.launch {
            mac.coordinator.hub.pairPrompt.collect { p ->
                if (p != null) { println("PROBE pair request from ${p.peerName}, code ${p.sas}: agreeing"); mac.coordinator.hub.respondToPair(p, true) }
            }
        }
        println("PROBE backup computer listening on 10.0.2.2:47000 (backups in ${File(home, "Backups")})")
        val minutes = System.getProperty("crossdrop.probeMinutes")?.toIntOrNull() ?: 6
        repeat(minutes * 6) {
            delay(10_000)
            val d = pairs.devices.value.firstOrNull()
            val files = File(home, "Backups").walkTopDown().filter { it.isFile }.map { it.relativeTo(File(home, "Backups")).path }.sorted().toList()
            println("PROBE paired=${d?.name} filed=${files.size} $files")
        }
        listener.close(); mac.close()
        home.deleteRecursively()
        Unit
    }


    @Test fun macHostsEmulatorScans() = runBlocking {
        assumeTrue(System.getProperty("crossdrop.linkProbe") == "1")
        val home = Files.createTempDirectory("crossdrop-probe").toFile()
        System.setProperty("crossdrop.home", File(home, "support").path)
        val host = LinkAndBackupTest.Node("Mac host", File(home, "host"))
        val listener = ServerSocket(47_000)
        host.server.stop()
        host.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            while (true) { val s = runCatching { listener.accept() }.getOrNull() ?: break; host.coordinator.hub.onInboundSocket(s) }
        }
        val code = WifiQr.payload("MacProbe", "pppppppp", "WPA", "10.0.2.2", 47_000, host.instanceId)
        println("PROBE code: $code")
        File(home.parentFile, "crossdrop-probe-code.txt").writeText(code)

        val guest = withTimeoutOrNull(180_000) {
            var p = host.announced.peers.value.values.firstOrNull()
            while (p == null) { delay(200); p = host.announced.peers.value.values.firstOrNull() }
            p
        } ?: error("no announcement within 3 minutes")
        val port = guest.endpoint!!.port
        println("PROBE announced: name=${guest.name} id=${guest.instanceId} from=${guest.endpoint}")
        ProcessBuilder("adb", "forward", "tcp:$port", "tcp:$port").inheritIO().start().waitFor()
        val payload = ByteArray(200_000) { (it * 17).toByte() }
        val src = File(home, "from-mac.bin").apply { writeBytes(payload) }
        host.coordinator.sendTo(guest.copy(endpoint = Endpoint("127.0.0.1", port)), listOf(src))
        println("PROBE sent offer to emulator, waiting for accept (tap Accept on the emulator)")
        val end = withTimeoutOrNull(120_000) {
            var s = host.coordinator.sessions.value.lastOrNull()
            while (s == null || !s.status.let { it == SessionStatus.COMPLETED || it == SessionStatus.FAILED || it == SessionStatus.DECLINED }) {
                delay(200); s = host.coordinator.sessions.value.lastOrNull()
            }
            s
        }
        println("PROBE result: ${end?.status} ${end?.error ?: ""}")
        ProcessBuilder("adb", "forward", "--remove", "tcp:$port").inheritIO().start().waitFor()
        listener.close(); host.close()
        home.deleteRecursively()
        check(end?.status == SessionStatus.COMPLETED) { "transfer did not complete: ${end?.status}" }
    }
}
