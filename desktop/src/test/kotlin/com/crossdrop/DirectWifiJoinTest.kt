package com.crossdrop

import com.crossdrop.core.Endpoint
import com.crossdrop.core.connect.DirectWifi
import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.discovery.AnnouncedPeers
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.net.DirectWifiJoin
import com.crossdrop.net.TransferCoordinator
import com.crossdrop.net.TransferServer
import com.crossdrop.platform.OsWifi
import com.crossdrop.platform.WifiJoiner
import com.crossdrop.storage.HistoryStore
import com.crossdrop.storage.InboxStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

/**
 * "Join a CrossDrop Wi-Fi" on a computer: the OS commands (pure, any host) and the join flow against a real
 * CrossDrop device on loopback standing in for the phone (the OS step is faked: no test may touch real Wi-Fi).
 */
class DirectWifiJoinTest {

    companion object {
        private val home: File = Files.createTempDirectory("crossdrop-directwifi").toFile()
        @JvmStatic @BeforeClass fun setup() { System.setProperty("crossdrop.home", File(home, "support").path) }
        @JvmStatic @AfterClass fun teardown() { home.deleteRecursively() }
    }

    // ---- the OS commands

    @Test
    fun `macOS finds the Wi-Fi device and reads networksetup's answers`() {
        val ports = """
            Hardware Port: Ethernet
            Device: en1
            Ethernet Address: aa:bb:cc:dd:ee:01

            Hardware Port: Wi-Fi
            Device: en0
            Ethernet Address: aa:bb:cc:dd:ee:00
        """.trimIndent()
        assertEquals("en0", WifiJoiner.macWifiDevice(ports))
        assertEquals("en2", WifiJoiner.macWifiDevice("Hardware Port: AirPort\nDevice: en2\n"))
        assertNull(WifiJoiner.macWifiDevice("Hardware Port: Thunderbolt Bridge\nDevice: bridge0\n"))
        assertEquals(
            listOf("networksetup", "-setairportnetwork", "en0", DirectWifi.SSID, DirectWifi.PASSWORD),
            WifiJoiner.macJoinCommand("en0", DirectWifi.SSID, DirectWifi.PASSWORD),
        )
        assertEquals(WifiJoiner.Result.Ok, WifiJoiner.macJoinResult("", 0))
        assertEquals(WifiJoiner.Result.NotFound, WifiJoiner.macJoinResult("Could not find network DIRECT-CD-CrossDrop.\n", 0))
        assertTrue(WifiJoiner.macJoinResult("Failed to join network DIRECT-CD-CrossDrop.\nError: -3905", 0) is WifiJoiner.Result.Failed)
        assertEquals(WifiJoiner.Result.NoWifi, WifiJoiner.macJoinResult("en5 is not a Wi-Fi interface.", 0))
    }

    @Test
    fun `Windows gets a manual-only profile and is read by exit code`() {
        val xml = WifiJoiner.windowsProfileXml(DirectWifi.SSID, DirectWifi.PASSWORD)
        assertTrue(xml.contains("<connectionMode>manual</connectionMode>"))   // never joins it by itself later
        assertTrue(xml.contains("<name>DIRECT-CD-CrossDrop</name>"))
        assertTrue(xml.contains("<keyMaterial>crossdrop123</keyMaterial>"))
        assertTrue(xml.contains("<authentication>WPA2PSK</authentication>"))
        // Anything a name could carry is escaped, so the profile stays well-formed.
        val odd = WifiJoiner.windowsProfileXml("a<b>&\"c'", "p&<>")
        assertTrue(odd.contains("<name>a&lt;b&gt;&amp;&quot;c&apos;</name>"))
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(odd.byteInputStream())
        assertEquals(listOf("netsh", "wlan", "connect", "name=DIRECT-CD-CrossDrop", "ssid=DIRECT-CD-CrossDrop"), WifiJoiner.windowsConnectCommand(DirectWifi.SSID))
        // A bare file name (run in its folder): a profile path with spaces can't split the argument.
        assertEquals("filename=crossdrop-wifi1.xml", WifiJoiner.windowsAddProfileCommand("crossdrop-wifi1.xml")[4])
        assertEquals(listOf("netsh", "wlan", "delete", "profile", "name=DIRECT-CD-CrossDrop"), WifiJoiner.windowsForgetCommand(DirectWifi.SSID))
        assertEquals(WifiJoiner.Result.Ok, WifiJoiner.windowsResult("Anfrage erfolgreich", 0, connecting = true))
        assertEquals(WifiJoiner.Result.NotFound, WifiJoiner.windowsResult("La red especificada no está disponible", 1, connecting = true))
        assertEquals(WifiJoiner.Result.NoWifi, WifiJoiner.windowsResult("There is no wireless interface on the system.", 1, connecting = false))
    }

    @Test
    fun `Linux uses one NetworkManager connection of its own, created never to autoconnect`() {
        val add = WifiJoiner.linuxAddCommand(DirectWifi.SSID, DirectWifi.PASSWORD)
        assertEquals(listOf("nmcli", "connection", "add"), add.take(3))
        assertTrue(add.containsAll(listOf("con-name", WifiJoiner.LINUX_CONNECTION, "ssid", DirectWifi.SSID, "wifi-sec.psk", DirectWifi.PASSWORD)))
        // Off from the start: an interrupted or failed join never leaves NetworkManager retrying it later.
        val i = add.indexOf("connection.autoconnect")
        assertEquals("no", add[i + 1])
        assertEquals(listOf("nmcli", "--wait", "30", "connection", "up", "id", "crossdrop-wifi"), WifiJoiner.linuxUpCommand())
        assertEquals(listOf("nmcli", "connection", "delete", "id", "crossdrop-wifi"), WifiJoiner.linuxForgetCommand())
        assertEquals(WifiJoiner.Result.Ok, WifiJoiner.linuxJoinResult("", 0))
        assertEquals(WifiJoiner.Result.NotFound, WifiJoiner.linuxJoinResult("Error: No network with SSID 'DIRECT-CD-CrossDrop' found.", 10))
        assertEquals(WifiJoiner.Result.NotFound, WifiJoiner.linuxJoinResult("Error: Connection activation failed: The Wi-Fi network could not be found", 4))
        assertEquals(WifiJoiner.Result.NotFound, WifiJoiner.linuxJoinResult("Error: Timeout expired (30 seconds)", 3))
        assertEquals(WifiJoiner.Result.NoWifi, WifiJoiner.linuxJoinResult("Error: No Wi-Fi device found.", 10))
        assertEquals(WifiJoiner.Result.Unsupported, WifiJoiner.linuxJoinResult("Error: NetworkManager is not running.", 8))
    }

    // ---- the join flow

    /** A CrossDrop device (the phone that made the network), listening on loopback. */
    private class Device(name: String, root: File) {
        val instanceId = ByteArray(8).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val engine = TransferEngine(SessionIdentity(), { name }, instanceId, os = "android")
        val server = TransferServer(scope, engine) { }
        val coordinator = TransferCoordinator(scope, engine, server, InboxStore(File(root, "inbox")), HistoryStore(File(root, "history.json")))
        val announced = AnnouncedPeers().also { coordinator.hub.announced = it }
        init { coordinator.start(); server.start() }
        fun close() { server.stop(); scope.cancel() }
    }

    /** The OS step, faked: what it was asked (in order), and what it answers. [gate] holds a join like a slow OS. */
    private class FakeWifi(var answer: WifiJoiner.Result = WifiJoiner.Result.Ok) : OsWifi {
        val joined = CopyOnWriteArrayList<Pair<String, String>>()
        val forgotten = CopyOnWriteArrayList<String>()
        val disconnects = CopyOnWriteArrayList<Boolean>()
        val ops = CopyOnWriteArrayList<String>()
        @Volatile var gate: java.util.concurrent.CountDownLatch? = null
        override val supported = true
        override fun join(ssid: String, password: String): WifiJoiner.Result {
            ops += "join"
            gate?.await(10, java.util.concurrent.TimeUnit.SECONDS)
            joined += ssid to password
            return answer
        }
        override fun forget(ssid: String, disconnect: Boolean) { ops += "forget"; forgotten += ssid; disconnects += disconnect }
    }

    private suspend fun <T> await(what: String, block: () -> T?): T =
        withTimeoutOrNull(20_000) {
            var v = block()
            while (v == null) { delay(50); v = block() }
            v!!
        } ?: error("timed out waiting for: $what")

    private val fast = DirectWifiJoin.Timing(addressWaitMs = 1_000, stepMs = 50, firstTries = 3, firstRetryMs = 100, reannounceMs = 300)

    @Test
    fun `one click joins the network, then the phone and this computer see each other`() = runBlocking {
        val root = Files.createTempDirectory("dwj").toFile()
        val phone = Device("Anna's phone", File(root, "phone"))
        val mac = Device("Mac", File(root, "mac"))
        val wifi = FakeWifi()
        await("phone listening") { phone.server.port.takeIf { it > 0 } }
        await("mac listening") { mac.server.port.takeIf { it > 0 } }
        val join = DirectWifiJoin(
            mac.scope, mac.engine, { mac.server.port }, mac.announced, wifi,
            localIp = { "192.168.49.23" },
            hostFor = { Endpoint("127.0.0.1", phone.server.port) },
            timing = fast,
        )
        try {
            join.join()
            val joined = await("linked") { (join.state.value as? DirectWifiJoin.State.Joined)?.takeIf { it.hostName != null } }
            assertEquals("Anna's phone", joined.hostName)
            assertEquals(listOf(DirectWifi.SSID to DirectWifi.PASSWORD), wifi.joined.toList())
            // The phone on our radar, and we on the phone's (the announcement).
            assertEquals("Anna's phone", await("phone on the computer's radar") { mac.announced.peers.value[phone.instanceId] }.name)
            assertEquals("Mac", await("computer on the phone's radar") { phone.announced.peers.value[mac.instanceId] }.name)

            join.leave()
            assertEquals(DirectWifiJoin.State.Idle, join.state.value)
            await("network forgotten") { wifi.forgotten.firstOrNull() }
            assertEquals(listOf(DirectWifi.SSID), wifi.forgotten.toList())
            assertEquals("still on it: forget and leave it", listOf(true), wifi.disconnects.toList())
            assertNull("the phone leaves our radar with the network", mac.announced.peers.value[phone.instanceId])
        } finally {
            phone.close(); mac.close(); root.deleteRecursively()
        }
    }

    @Test
    fun `no network in range is said plainly and nothing is left to forget`() = runBlocking {
        val root = Files.createTempDirectory("dwj").toFile()
        val mac = Device("Mac", File(root, "mac"))
        val wifi = FakeWifi(WifiJoiner.Result.NotFound)
        val join = DirectWifiJoin(mac.scope, mac.engine, { 1 }, mac.announced, wifi, localIp = { "192.168.1.5" }, timing = fast)
        try {
            join.join()
            assertEquals(
                DirectWifiJoin.Failure.NOT_FOUND,
                await("failed") { (join.state.value as? DirectWifiJoin.State.Failed)?.reason },
            )
            // A failed join may still have left the network remembered (or activating): it is forgotten anyway.
            assertEquals(listOf(DirectWifi.SSID), await("forgotten after the failure") { wifi.forgotten.toList().takeIf { it.isNotEmpty() } })
            join.leave()
            delay(200)
            assertEquals("nothing left to forget twice", 1, wifi.forgotten.size)
            wifi.answer = WifiJoiner.Result.NoWifi
            join.join()
            assertEquals(DirectWifiJoin.Failure.NO_WIFI, await("no wifi") { (join.state.value as? DirectWifiJoin.State.Failed)?.reason })
        } finally {
            mac.close(); root.deleteRecursively()
        }
    }

    @Test
    fun `joined but never got an address on it - not stayed on, and forgotten`() = runBlocking {
        val root = Files.createTempDirectory("dwj").toFile()
        val mac = Device("Mac", File(root, "mac"))
        val wifi = FakeWifi()
        val join = DirectWifiJoin(mac.scope, mac.engine, { 1 }, mac.announced, wifi, localIp = { "192.168.1.5" }, timing = fast)
        try {
            join.join()
            assertEquals(DirectWifiJoin.Failure.FAILED, await("failed") { (join.state.value as? DirectWifiJoin.State.Failed)?.reason })
            assertEquals(listOf(DirectWifi.SSID), await("forgotten") { wifi.forgotten.toList().takeIf { it.isNotEmpty() } })
        } finally {
            mac.close(); root.deleteRecursively()
        }
    }

    @Test
    fun `the phone ending its network is noticed and the network forgotten`() = runBlocking {
        val root = Files.createTempDirectory("dwj").toFile()
        val phone = Device("Anna's phone", File(root, "phone"))
        val mac = Device("Mac", File(root, "mac"))
        val wifi = FakeWifi()
        val address = java.util.concurrent.atomic.AtomicReference("192.168.49.23")
        await("phone listening") { phone.server.port.takeIf { it > 0 } }
        val join = DirectWifiJoin(
            mac.scope, mac.engine, { mac.server.port }, mac.announced, wifi,
            localIp = { address.get() },
            hostFor = { Endpoint("127.0.0.1", phone.server.port) },
            timing = fast,
        )
        try {
            join.join()
            await("linked") { (join.state.value as? DirectWifiJoin.State.Joined)?.hostName }
            address.set("192.168.1.5")      // the phone stopped it: the OS went back to the usual network
            assertEquals(DirectWifiJoin.Failure.LOST, await("lost") { (join.state.value as? DirectWifiJoin.State.Failed)?.reason })
            assertEquals(listOf(DirectWifi.SSID), await("forgotten") { wifi.forgotten.toList().takeIf { it.isNotEmpty() } })
            assertEquals("already off it: forget, don't power-cycle Wi-Fi", listOf(false), wifi.disconnects.toList())
            assertFalse(mac.announced.peers.value.containsKey(phone.instanceId))
        } finally {
            phone.close(); mac.close(); root.deleteRecursively()
        }
    }

    @Test
    fun `quitting forgets the network at once`() = runBlocking {
        val root = Files.createTempDirectory("dwj").toFile()
        val phone = Device("Anna's phone", File(root, "phone"))
        val mac = Device("Mac", File(root, "mac"))
        val wifi = FakeWifi()
        await("phone listening") { phone.server.port.takeIf { it > 0 } }
        val join = DirectWifiJoin(
            mac.scope, mac.engine, { mac.server.port }, mac.announced, wifi,
            localIp = { "192.168.49.23" }, hostFor = { Endpoint("127.0.0.1", phone.server.port) }, timing = fast,
        )
        try {
            join.join()
            await("linked") { (join.state.value as? DirectWifiJoin.State.Joined)?.hostName }
            join.shutdown()
            assertEquals("forgotten before shutdown returns", listOf(DirectWifi.SSID), wifi.forgotten.toList())
        } finally {
            phone.close(); mac.close(); root.deleteRecursively()
        }
    }

    /** The reviewer's case: Leave while the OS is still joining. The OS finishes the join; we must not stay on it. */
    @Test
    fun `leave during a slow join forgets the network once the OS is done`() = runBlocking {
        val root = Files.createTempDirectory("dwj").toFile()
        val mac = Device("Mac", File(root, "mac"))
        val wifi = FakeWifi().apply { gate = java.util.concurrent.CountDownLatch(1) }
        val join = DirectWifiJoin(mac.scope, mac.engine, { 1 }, mac.announced, wifi, localIp = { "192.168.49.23" }, timing = fast)
        try {
            join.join()
            await("OS asked to join") { wifi.ops.firstOrNull() }
            join.leave()
            assertEquals(DirectWifiJoin.State.Idle, join.state.value)
            wifi.gate!!.countDown()                       // the OS join completes after the Leave
            await("forgotten") { wifi.forgotten.firstOrNull() }
            delay(300)
            assertEquals("the Leave stays a Leave", DirectWifiJoin.State.Idle, join.state.value)
            assertEquals(listOf("join", "forget"), wifi.ops.toList())
        } finally {
            mac.close(); root.deleteRecursively()
        }
    }

    @Test
    fun `quitting during a slow join still forgets the network`() = runBlocking {
        val root = Files.createTempDirectory("dwj").toFile()
        val mac = Device("Mac", File(root, "mac"))
        val wifi = FakeWifi().apply { gate = java.util.concurrent.CountDownLatch(1) }
        val join = DirectWifiJoin(mac.scope, mac.engine, { 1 }, mac.announced, wifi, localIp = { "192.168.1.5" }, timing = fast)
        try {
            join.join()
            await("OS asked to join") { wifi.ops.firstOrNull() }
            Thread { Thread.sleep(300); wifi.gate!!.countDown() }.start()
            join.shutdown()                               // waits for the OS join, then forgets
            assertEquals(listOf("join", "forget"), wifi.ops.toList())
        } finally {
            mac.close(); root.deleteRecursively()
        }
    }

    /** Leave, then Join again at once: the Leave's forget must not remove the network the new join is using. */
    @Test
    fun `leave then join again does not forget the new join's network`() = runBlocking {
        val root = Files.createTempDirectory("dwj").toFile()
        val phone = Device("Anna's phone", File(root, "phone"))
        val mac = Device("Mac", File(root, "mac"))
        val wifi = FakeWifi().apply { gate = java.util.concurrent.CountDownLatch(1) }
        await("phone listening") { phone.server.port.takeIf { it > 0 } }
        val join = DirectWifiJoin(
            mac.scope, mac.engine, { mac.server.port }, mac.announced, wifi,
            localIp = { "192.168.49.23" }, hostFor = { Endpoint("127.0.0.1", phone.server.port) }, timing = fast,
        )
        try {
            join.join()
            await("OS asked to join") { wifi.ops.firstOrNull() }
            join.leave()
            join.join()
            wifi.gate!!.countDown()
            val joined = await("linked by the second join") { (join.state.value as? DirectWifiJoin.State.Joined)?.hostName }
            assertEquals("Anna's phone", joined)
            delay(500)
            assertEquals("the last word is a join, not a forget after it", "join", wifi.ops.last())
            assertTrue(join.state.value is DirectWifiJoin.State.Joined)
        } finally {
            join.shutdown(); phone.close(); mac.close(); root.deleteRecursively()
        }
    }

    /** A crash or force quit left the network remembered: the next launch forgets it. */
    @Test
    fun `a network left by a crash is forgotten at the next launch`() = runBlocking {
        val root = Files.createTempDirectory("dwj").toFile()
        val mac = Device("Mac", File(root, "mac"))
        val wifi = FakeWifi()
        val marker = DirectWifiJoin.JoinedMarker.InMemory().apply { joined = true }
        val join = DirectWifiJoin(mac.scope, mac.engine, { 1 }, mac.announced, wifi, localIp = { "192.168.1.5" }, timing = fast, marker = marker)
        try {
            join.cleanUpLeftover()
            assertEquals(listOf(DirectWifi.SSID), await("forgotten") { wifi.forgotten.toList().takeIf { it.isNotEmpty() } })
            assertFalse(marker.joined)
            // Nothing remembered: nothing to do, the OS isn't even asked.
            join.cleanUpLeftover(); join.shutdown()
            assertEquals(1, wifi.forgotten.size)
        } finally {
            mac.close(); root.deleteRecursively()
        }
    }
}
