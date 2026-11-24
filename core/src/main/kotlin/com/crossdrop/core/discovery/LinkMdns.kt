package com.crossdrop.core.discovery

import com.crossdrop.core.CrossDrop
import com.crossdrop.core.DiscoverySource
import com.crossdrop.core.Endpoint
import com.crossdrop.core.Peer
import com.crossdrop.core.UNNAMED_PEER
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** What this device says about itself on the link. A null advert (no listener port yet) means "say nothing". */
data class MdnsAdvert(val instanceId: String, val name: String, val port: Int, val os: String)

/** The datagrams of one network interface. The real one is [MulticastTransport]; tests use an in-memory bus. */
interface MdnsTransport : AutoCloseable {
    /** Starts receiving: [onPacket] gets every datagram with its sender, from any thread. Throws if the link can't be joined. */
    fun open(onPacket: (ByteArray, InetSocketAddress) -> Unit)
    fun sendMulticast(data: ByteArray)
    fun sendUnicast(data: ByteArray, to: InetSocketAddress)
}

/**
 * CrossDrop's own mDNS on one network interface: it answers for `_crossdrop._tcp` and browses for it.
 *
 * Why it exists: the phone's system mDNS service does not run on the hotspot a phone hosts (a Honor on
 * Android 17 never opened a socket there, even when asked for the network by name), so a laptop joined to the
 * phone's hotspot and the phone never saw each other. This speaks the same protocol the laptop's Bonjour or
 * JmDNS does, so nothing on the other side changes. It is used only on interfaces the system's service
 * skips; on the Wi-Fi the phone joins, `NsdManager` still does the work.
 *
 * Deliberately small: one service type, IPv4, no probing or conflict handling (the instance name carries the
 * random per-launch id), known-answer suppression for our PTR only. Everything arrives through [handle] and
 * [tick], which take the lock, so tests can drive it with a fake clock and no threads.
 */
class LinkMdns(
    private val transport: MdnsTransport,
    private val local: Inet4Address,
    /** Whether a sender is on this interface's own subnet. Wildcard sockets also hear other interfaces; those are dropped. */
    private val onLink: (InetAddress) -> Boolean,
    private val advert: () -> MdnsAdvert?,
    private val onPeers: (Map<String, Peer>) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Multicast loops our own datagrams back to us; drop what came from our own address. Off only for a test on one machine. */
    private val dropOwnAddress: Boolean = true,
) {
    private class Seen(val instance: String) {
        var port = 0
        var target: String? = null
        var txt: Map<String, String>? = null
        var from: Inet4Address? = null
        var lastSeen = 0L
    }

    private val lock = Any()
    private val seen = LinkedHashMap<String, Seen>()          // by lower-case instance name
    private val hostAddrs = HashMap<String, Inet4Address>()    // by lower-case host name
    private var peers: Map<String, Peer> = emptyMap()

    private var lastAdvert: MdnsAdvert? = null
    private var announcesLeft = 0
    private var nextAnnounceAt = 0L
    private var queryStep = 0
    private var nextQueryAt = 0L
    private var lastMulticastAnswerAt = 0L
    private var lastResolveAt = 0L
    private var started = false

    private var executor: java.util.concurrent.ScheduledExecutorService? = null

    /** Joins the link and starts the timer. Tests pass `autoTick = false` and call [tick] with their own clock. */
    fun start(autoTick: Boolean = true) {
        synchronized(lock) {
            if (started) return
            started = true
            nextQueryAt = clock()
        }
        transport.open { data, from -> handle(data, from) }
        if (!autoTick) return
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mdns-link").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay({ runCatching { tick() } }, 0, TICK_MS, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        executor?.shutdownNow(); executor = null
        synchronized(lock) {
            if (!started) return
            started = false
            lastAdvert?.let { a -> runCatching { transport.sendMulticast(response(a, ttlOverride = 0)) } }
            lastAdvert = null
            seen.clear(); hostAddrs.clear()
            publish()
        }
        runCatching { transport.close() }
    }

    // ----------------------------------------------------------------- receive --

    fun handle(data: ByteArray, from: InetSocketAddress, length: Int = data.size) {
        val src = from.address as? Inet4Address ?: return
        if ((dropOwnAddress && src == local) || !onLink(src)) return
        val msg = MdnsCodec.decode(data, length) ?: return
        synchronized(lock) {
            if (!started) return
            val now = clock()
            if (msg.response) absorb(msg, src, now) else answer(msg, from, now)
        }
    }

    private fun absorb(msg: MdnsMessage, src: Inet4Address, now: Long) {
        val me = advert()?.instanceId
        val touched = LinkedHashSet<String>()
        for (r in msg.records) when (r) {
            is ARecord -> if (onLink(r.address)) hostAddrs[r.name.lowercase()] = r.address
            is PtrRecord -> if (r.name.equals(SERVICE, true) && isInstance(r.target)) {
                if (r.ttl == 0L) forget(r.target) else touched += note(r.target, src, now)
            }
            is SrvRecord -> if (isInstance(r.name)) {
                if (r.ttl == 0L) forget(r.name) else {
                    val s = seen.getOrPut(r.name.lowercase()) { Seen(r.name) }
                    s.port = r.port; s.target = r.target; touch(s, src, now); touched += r.name.lowercase()
                }
            }
            is TxtRecord -> if (isInstance(r.name) && r.ttl != 0L) {
                val s = seen.getOrPut(r.name.lowercase()) { Seen(r.name) }
                s.txt = r.entries; touch(s, src, now); touched += r.name.lowercase()
            }
        }
        // Something we heard of but cannot complete: ask for the rest once, not on every packet.
        val incomplete = touched.filter { k -> seen[k]?.let { it.port == 0 || it.txt == null } == true }
        if (incomplete.isNotEmpty() && now - lastResolveAt >= RESOLVE_GAP_MS) {
            lastResolveAt = now
            val questions = incomplete.mapNotNull { seen[it]?.instance }.map { MdnsQuestion(it, MdnsCodec.TYPE_ANY) }
            runCatching { transport.sendMulticast(MdnsCodec.encodeQuery(questions)) }
        }
        publish(me)
    }

    private fun note(instance: String, src: Inet4Address, now: Long): String {
        val s = seen.getOrPut(instance.lowercase()) { Seen(instance) }
        touch(s, src, now)
        return instance.lowercase()
    }

    private fun touch(s: Seen, src: Inet4Address, now: Long) { s.from = src; s.lastSeen = now }

    private fun forget(instance: String) { seen.remove(instance.lowercase()) }

    private fun isInstance(name: String) = name.endsWith(".$SERVICE", ignoreCase = true) && name.length > SERVICE.length + 1

    // ------------------------------------------------------------------ answer --

    private fun answer(msg: MdnsMessage, from: InetSocketAddress, now: Long) {
        val a = advert() ?: return
        val instance = instanceName(a)
        var wanted = false
        var onlyPtr = true
        for (q in msg.questions) {
            val any = q.type == MdnsCodec.TYPE_ANY
            when {
                q.name.equals(SERVICE, true) && (any || q.type == MdnsCodec.TYPE_PTR) -> wanted = true
                q.name.equals(instance, true) && (any || q.type == MdnsCodec.TYPE_SRV || q.type == MdnsCodec.TYPE_TXT) -> { wanted = true; onlyPtr = false }
                q.name.equals(hostName(a), true) && (any || q.type == MdnsCodec.TYPE_A) -> { wanted = true; onlyPtr = false }
            }
        }
        if (!wanted) return
        // The asker already holds a fresh copy of our service record (RFC 6762 7.1).
        if (onlyPtr && msg.records.any { it is PtrRecord && it.name.equals(SERVICE, true) && it.target.equals(instance, true) && it.ttl >= PTR_TTL / 2 }) return
        val legacy = from.port != MDNS_PORT      // a plain resolver asking from its own port wants a direct reply
        if (legacy) {
            runCatching { transport.sendUnicast(response(a, id = msg.id), from) }
        } else if (now - lastMulticastAnswerAt >= ANSWER_GAP_MS) {
            lastMulticastAnswerAt = now
            runCatching { transport.sendMulticast(response(a)) }
        }
    }

    // -------------------------------------------------------------------- tick --

    fun tick() {
        synchronized(lock) {
            if (!started) return
            val now = clock()
            val a = advert()
            if (a != lastAdvert) {
                lastAdvert = a
                if (a != null) { announcesLeft = ANNOUNCES; nextAnnounceAt = now }
            }
            if (a != null && announcesLeft > 0 && now >= nextAnnounceAt) {
                announcesLeft--; nextAnnounceAt = now + 1_000
                runCatching { transport.sendMulticast(response(a)) }
            }
            if (now >= nextQueryAt) {
                runCatching { transport.sendMulticast(MdnsCodec.encodeQuery(listOf(MdnsQuestion(SERVICE, MdnsCodec.TYPE_PTR)))) }
                nextQueryAt = now + QUERY_SCHEDULE_MS[minOf(queryStep++, QUERY_SCHEDULE_MS.size - 1)]
            }
            val before = seen.size
            seen.values.removeAll { now - it.lastSeen > EXPIRE_MS }
            if (seen.size != before) publish(a?.instanceId)
        }
    }

    // ------------------------------------------------------------------- build --

    private fun response(a: MdnsAdvert, id: Int = 0, ttlOverride: Long? = null): ByteArray {
        val ptrTtl = ttlOverride ?: PTR_TTL
        val ttl = ttlOverride ?: RECORD_TTL
        val txt = linkedMapOf("id" to a.instanceId, "name" to a.name.take(48), CrossDrop.TXT_PROBE to "1", CrossDrop.TXT_OS to a.os)
        return MdnsCodec.encodeResponse(
            answers = listOf(PtrRecord(SERVICE, ptrTtl, instanceName(a))),
            additionals = listOf(
                SrvRecord(instanceName(a), ttl, a.port, hostName(a)),
                TxtRecord(instanceName(a), ttl, txt),
                ARecord(hostName(a), ttl, local),
            ),
            id = id,
        )
    }

    private fun instanceName(a: MdnsAdvert) = "crossdrop-${a.instanceId}.$SERVICE"
    private fun hostName(a: MdnsAdvert) = "crossdrop-${a.instanceId.take(8)}.local"

    private fun publish(me: String? = advert()?.instanceId) {
        val now = LinkedHashMap<String, Peer>()
        for (s in seen.values) {
            val txt = s.txt
            val id = txt?.get("id") ?: s.instance.substringBefore('.').removePrefix("crossdrop-")
            if (id.isEmpty() || id == me || s.port == 0) continue
            val host = (s.target?.let { hostAddrs[it.lowercase()] } ?: s.from)?.hostAddress ?: continue
            val peer = Peer(
                instanceId = id,
                name = txt?.get("name")?.takeIf { it.isNotBlank() } ?: peers[id]?.name ?: UNNAMED_PEER,
                endpoint = Endpoint(host, s.port),
                sources = setOf(DiscoverySource.NSD),
                probeable = txt?.get(CrossDrop.TXT_PROBE) == "1",
                platform = txt?.get(CrossDrop.TXT_OS)?.takeIf { it.length <= 16 },
            )
            val old = peers[id]
            now[id] = if (old != null && old.copy(lastSeenMs = peer.lastSeenMs) == peer) old else peer
        }
        if (now != peers) { peers = now; onPeers(now) }
    }

    companion object {
        const val MDNS_PORT = 5353
        /** `_crossdrop._tcp.local`: the service type, as it travels in DNS. */
        val SERVICE = CrossDrop.NSD_SERVICE_TYPE.trimEnd('.') + ".local"

        private const val TICK_MS = 500L
        private const val ANNOUNCES = 2
        private const val PTR_TTL = 4500L
        private const val RECORD_TTL = 120L
        private const val ANSWER_GAP_MS = 1_000L
        private const val RESOLVE_GAP_MS = 1_000L
        /** A peer not heard from for this long is gone; queries every 30 s keep a live one fresh. */
        private const val EXPIRE_MS = 100_000L
        /** Pause after each of our PTR queries: quick at first so a link that just came up fills fast, then a minute-ish pace. */
        private val QUERY_SCHEDULE_MS = longArrayOf(1_000, 2_000, 4_000, 8_000, 15_000, 30_000)
    }
}
