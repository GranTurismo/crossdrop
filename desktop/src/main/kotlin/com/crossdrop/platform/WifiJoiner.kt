package com.crossdrop.platform

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Joins and leaves a Wi-Fi network through the operating system's own tools, for "Join a CrossDrop Wi-Fi" on a
 * computer (the network an Android phone makes, see `core/connect/DirectWifi`):
 *
 * - **macOS**: `networksetup -setairportnetwork <device> <name> <password>` (the device from
 *   `-listallhardwareports`); forgetting removes it from the preferred networks (so the Mac never rejoins a
 *   stranger's CrossDrop Wi-Fi by itself later) and, if the Mac is still on it, switches Wi-Fi off and on: removing a
 *   preferred network doesn't disconnect from it (seen on macOS 27), and a power cycle makes the Mac rejoin its usual
 *   network.
 * - **Windows**: a per-user WLAN profile with `connectionMode` **manual** (Windows never connects to it on its own),
 *   `netsh wlan add profile` + `netsh wlan connect` (retried for a while: Windows only connects to networks in its
 *   cached scan list, and the phone's network may be seconds old); forgetting deletes the profile, which also
 *   disconnects, and Windows goes back to its usual network.
 * - **Linux**: NetworkManager. The connection is *created* with autoconnect off (`nmcli connection add …
 *   connection.autoconnect no`), then brought up; a failed join deletes it, so NetworkManager never retries it in the
 *   background. Forgetting deletes it, which also disconnects.
 *
 * The command lines and the reading of their output are pure functions (tested on any host in `DirectWifiJoinTest`);
 * only [SystemWifi] starts processes. Whether the join really worked is decided by the caller from this computer's
 * address (it moves onto the phone's network), which doesn't depend on any tool's wording or language.
 */
object WifiJoiner {
    sealed interface Result {
        /** The system took the request (the caller still waits for the address to move). */
        data object Ok : Result
        /** No such network in range. */
        data object NotFound : Result
        /** This computer has no Wi-Fi (or it is switched off). */
        data object NoWifi : Result
        /** The tool isn't there or can't be used here (no NetworkManager, an unknown OS). */
        data object Unsupported : Result
        data class Failed(val detail: String) : Result
    }

    /** NetworkManager connection name we create (and the only one we ever delete). */
    const val LINUX_CONNECTION = "crossdrop-wifi"

    // ---- macOS

    /** The Wi-Fi device ("en0") from `networksetup -listallhardwareports`, or null when there is none. */
    fun macWifiDevice(listAllHardwarePorts: String): String? {
        var port: String? = null
        for (raw in listAllHardwarePorts.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("Hardware Port:") -> port = line.substringAfter(':').trim()
                line.startsWith("Device:") && (port == "Wi-Fi" || port == "AirPort") ->
                    return line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    fun macJoinCommand(device: String, ssid: String, password: String) =
        listOf("networksetup", "-setairportnetwork", device, ssid, password)

    fun macForgetCommand(device: String, ssid: String) =
        listOf("networksetup", "-removepreferredwirelessnetwork", device, ssid)

    /** `networksetup` prints nothing when it joined, and a sentence when it didn't (often with exit code 0). */
    fun macJoinResult(output: String, exit: Int): Result {
        val text = output.trim()
        return when {
            text.isEmpty() && exit == 0 -> Result.Ok
            text.contains("Could not find network", ignoreCase = true) -> Result.NotFound
            text.contains("is not a Wi-Fi interface", ignoreCase = true) ||
                text.contains("power", ignoreCase = true) && text.contains("off", ignoreCase = true) -> Result.NoWifi
            else -> Result.Failed(text.ifEmpty { "exit $exit" })
        }
    }

    // ---- Windows

    /** A WPA2-personal profile that Windows only uses when asked (manual), never on its own. */
    fun windowsProfileXml(ssid: String, password: String): String {
        val name = xml(ssid)
        val key = xml(password)
        return """<?xml version="1.0"?>
<WLANProfile xmlns="http://www.microsoft.com/networking/WLAN/profile/v1">
  <name>$name</name>
  <SSIDConfig><SSID><name>$name</name></SSID></SSIDConfig>
  <connectionType>ESS</connectionType>
  <connectionMode>manual</connectionMode>
  <MSM>
    <security>
      <authEncryption><authentication>WPA2PSK</authentication><encryption>AES</encryption><useOneX>false</useOneX></authEncryption>
      <sharedKey><keyType>passPhrase</keyType><protected>false</protected><keyMaterial>$key</keyMaterial></sharedKey>
    </security>
  </MSM>
</WLANProfile>
"""
    }

    /** Run in the profile's folder with the bare file name: a path with spaces (`C:\Users\John Smith`) can't break it. */
    fun windowsAddProfileCommand(profileFileName: String) =
        listOf("netsh", "wlan", "add", "profile", "filename=$profileFileName", "user=current")

    fun windowsConnectCommand(ssid: String) = listOf("netsh", "wlan", "connect", "name=$ssid", "ssid=$ssid")

    fun windowsForgetCommand(ssid: String) = listOf("netsh", "wlan", "delete", "profile", "name=$ssid")

    /**
     * `netsh` words its answers in the system's language, so only the exit code is read, plus the one case worth
     * telling apart: no wireless interface at all (its English wording; other languages fall under "failed").
     */
    fun windowsResult(output: String, exit: Int, connecting: Boolean): Result = when {
        exit == 0 -> Result.Ok
        output.contains("no wireless interface", ignoreCase = true) ||
            output.contains("Wireless AutoConfig Service", ignoreCase = true) -> Result.NoWifi
        // The profile is in place, so a refused connect means the network isn't in range.
        connecting -> Result.NotFound
        else -> Result.Failed(output.trim().ifEmpty { "exit $exit" })
    }

    // ---- Linux (NetworkManager)

    fun linuxRescanCommand() = listOf("nmcli", "device", "wifi", "rescan")

    /**
     * Created with autoconnect **off** from the start: ours must never come back by itself (a stranger's CrossDrop Wi-Fi
     * in range later would take this computer offline), not even after a failed or interrupted join.
     */
    fun linuxAddCommand(ssid: String, password: String) = listOf(
        "nmcli", "connection", "add", "type", "wifi", "ifname", "*", "con-name", LINUX_CONNECTION, "ssid", ssid,
        "wifi-sec.key-mgmt", "wpa-psk", "wifi-sec.psk", password, "connection.autoconnect", "no",
    )

    fun linuxUpCommand() = listOf("nmcli", "--wait", "30", "connection", "up", "id", LINUX_CONNECTION)

    fun linuxForgetCommand() = listOf("nmcli", "connection", "delete", "id", LINUX_CONNECTION)

    /**
     * nmcli's documented exit codes: 3 = timed out, 4 = activation failed (the network isn't there: the password is
     * fixed and right), 8 = NetworkManager not running, 10 = no such connection/device/network.
     */
    fun linuxJoinResult(output: String, exit: Int): Result = when (exit) {
        0 -> Result.Ok
        3, 4 -> Result.NotFound
        10 -> if (output.contains("device", ignoreCase = true) && !output.contains("SSID", ignoreCase = true)) Result.NoWifi else Result.NotFound
        8 -> Result.Unsupported
        else -> Result.Failed(output.trim().ifEmpty { "exit $exit" })
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

/** The operating system's Wi-Fi, as "Join a CrossDrop Wi-Fi" needs it. A test passes a fake. */
interface OsWifi {
    val supported: Boolean
    fun join(ssid: String, password: String): WifiJoiner.Result
    /**
     * Forgets the network, so this computer never joins it again by itself; with [disconnect] (we are still on it) also
     * leaves it, so the OS goes back to its usual network.
     */
    fun forget(ssid: String, disconnect: Boolean)
}

/** The real one: runs the commands [WifiJoiner] builds for the OS we're on. */
class SystemWifi(private val platform: Platform = Platform.current) : OsWifi {
    override val supported: Boolean get() = platform == Platform.MAC || platform == Platform.WINDOWS || platform == Platform.LINUX

    override fun join(ssid: String, password: String): WifiJoiner.Result = runCatching {
        when (platform) {
            Platform.MAC -> {
                val device = macDevice() ?: return WifiJoiner.Result.NoWifi
                val (out, exit) = run(WifiJoiner.macJoinCommand(device, ssid, password), 45)
                WifiJoiner.macJoinResult(out, exit)
            }
            Platform.WINDOWS -> {
                val file = File.createTempFile("crossdrop-wifi", ".xml")
                try {
                    file.writeText(WifiJoiner.windowsProfileXml(ssid, password))
                    val (addOut, addExit) = run(WifiJoiner.windowsAddProfileCommand(file.name), 20, dir = file.parentFile)
                    val added = WifiJoiner.windowsResult(addOut, addExit, connecting = false)
                    if (added != WifiJoiner.Result.Ok) return added
                } finally {
                    file.delete()      // it holds the password in plain text
                }
                // Windows connects only to networks in its last scan, which refreshes every few seconds.
                var result: WifiJoiner.Result = WifiJoiner.Result.NotFound
                for (attempt in 1..WINDOWS_CONNECT_TRIES) {
                    val (out, exit) = run(WifiJoiner.windowsConnectCommand(ssid), 20)
                    result = WifiJoiner.windowsResult(out, exit, connecting = true)
                    if (result != WifiJoiner.Result.NotFound) break
                    Thread.sleep(WINDOWS_CONNECT_PAUSE_MS)
                }
                result
            }
            Platform.LINUX -> {
                run(WifiJoiner.linuxForgetCommand(), 10)          // a leftover from a crash would clash by name
                run(WifiJoiner.linuxRescanCommand(), 15)          // the phone's network may be seconds old
                val (addOut, addExit) = run(WifiJoiner.linuxAddCommand(ssid, password), 15)
                if (addExit != 0) return WifiJoiner.linuxJoinResult(addOut, addExit)
                val (out, exit) = run(WifiJoiner.linuxUpCommand(), 45)
                WifiJoiner.linuxJoinResult(out, exit).also {
                    // Not up: don't leave it behind (forgetting also runs, this is the first line of defence).
                    if (it != WifiJoiner.Result.Ok) run(WifiJoiner.linuxForgetCommand(), 10)
                }
            }
            Platform.OTHER -> WifiJoiner.Result.Unsupported
        }
    }.getOrElse { e ->
        // The tool itself is missing (no nmcli, no netsh): nothing to fall back on.
        if (e is java.io.IOException) WifiJoiner.Result.Unsupported else WifiJoiner.Result.Failed(e.message ?: e.toString())
    }

    override fun forget(ssid: String, disconnect: Boolean) {
        runCatching {
            when (platform) {
                Platform.MAC -> macDevice()?.let { device ->
                    run(WifiJoiner.macForgetCommand(device, ssid), 15)
                    // Removing a preferred network doesn't leave it; Wi-Fi off and on rejoins the usual network.
                    if (disconnect) {
                        run(listOf("networksetup", "-setairportpower", device, "off"), 15)
                        Thread.sleep(2_000)
                        run(listOf("networksetup", "-setairportpower", device, "on"), 15)
                    }
                }
                Platform.WINDOWS -> run(WifiJoiner.windowsForgetCommand(ssid), 15)   // deleting the profile disconnects too
                Platform.LINUX -> run(WifiJoiner.linuxForgetCommand(), 15)            // deleting the connection disconnects too
                Platform.OTHER -> Unit
            }
        }.onFailure { Log.w(TAG, "forgetting $ssid failed", it) }
    }

    private fun macDevice(): String? = WifiJoiner.macWifiDevice(run(listOf("networksetup", "-listallhardwareports"), 10).first)

    /** Runs [cmd], returns its output (stdout and stderr together) and exit code; a hung tool is killed. */
    private fun run(cmd: List<String>, timeoutSeconds: Long, dir: File? = null): Pair<String, Int> {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).apply { if (dir != null) directory(dir) }.start()
        val out = StringBuilder()
        val reader = Thread { runCatching { p.inputStream.bufferedReader().use { r -> r.forEachLine { out.appendLine(it) } } } }
        reader.isDaemon = true
        reader.start()
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            return out.toString() to -1
        }
        reader.join(2_000)
        // Never log the command line: it carries the password (a public one, but still).
        Log.i(TAG, "${cmd.take(3).joinToString(" ")} … -> ${p.exitValue()}")
        return out.toString() to p.exitValue()
    }

    private companion object {
        const val TAG = "SystemWifi"
        const val WINDOWS_CONNECT_TRIES = 6
        const val WINDOWS_CONNECT_PAUSE_MS = 3_000L
    }
}
