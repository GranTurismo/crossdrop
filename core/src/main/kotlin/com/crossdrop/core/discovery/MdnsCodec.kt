package com.crossdrop.core.discovery

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.InetAddress

/** The part of a DNS question CrossDrop cares about. [unicast] is the QU bit (a querier that wants a unicast answer). */
data class MdnsQuestion(val name: String, val type: Int, val unicast: Boolean = false)

/** One resource record, only of the kinds CrossDrop's service uses. Names have no trailing dot. [ttl] is in seconds. */
sealed interface MdnsRecord {
    val name: String
    val ttl: Long
}

data class PtrRecord(override val name: String, override val ttl: Long, val target: String) : MdnsRecord
data class SrvRecord(override val name: String, override val ttl: Long, val port: Int, val target: String) : MdnsRecord
data class TxtRecord(override val name: String, override val ttl: Long, val entries: Map<String, String>) : MdnsRecord
data class ARecord(override val name: String, override val ttl: Long, val address: Inet4Address) : MdnsRecord

/** A decoded packet. Answer, authority and additional sections are folded into [records]: a cache treats them alike. */
data class MdnsMessage(val response: Boolean, val id: Int, val questions: List<MdnsQuestion>, val records: List<MdnsRecord>)

/**
 * DNS wire format for multicast DNS (RFC 1035 + RFC 6762), just enough to advertise and browse one service type.
 *
 * The decoder faces the local network, so it is strict: every length is checked against the packet, name
 * compression may not loop, and anything odd makes the whole packet "not for us" (null) instead of throwing.
 */
object MdnsCodec {
    const val TYPE_A = 1
    const val TYPE_PTR = 12
    const val TYPE_TXT = 16
    const val TYPE_SRV = 33
    const val TYPE_ANY = 255

    private const val CLASS_IN = 1
    private const val CACHE_FLUSH = 0x8000
    private const val FLAG_RESPONSE = 0x8000
    private const val FLAG_AUTHORITATIVE = 0x0400

    const val MAX_PACKET = 9000
    private const val MAX_RECORDS = 128
    private const val MAX_NAME = 255
    private const val MAX_NAME_JUMPS = 16

    // ------------------------------------------------------------------ decode --

    fun decode(data: ByteArray, length: Int = data.size): MdnsMessage? {
        if (length < 12 || length > MAX_PACKET || length > data.size) return null
        return try {
            Reader(data, length).message()
        } catch (_: MalformedException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    private class MalformedException : Exception()

    private class Reader(val b: ByteArray, val len: Int) {
        var pos = 0

        fun u8(): Int { if (pos + 1 > len) throw MalformedException(); return b[pos++].toInt() and 0xFF }
        fun u16(): Int { val h = u8(); return (h shl 8) or u8() }
        fun u32(): Long { val h = u16().toLong(); return (h shl 16) or u16().toLong() }

        fun message(): MdnsMessage {
            val id = u16()
            val flags = u16()
            val qd = u16(); val an = u16(); val ns = u16(); val ar = u16()
            if (qd + an + ns + ar > MAX_RECORDS) throw MalformedException()
            val questions = ArrayList<MdnsQuestion>(qd)
            repeat(qd) {
                val name = name()
                val type = u16()
                val cls = u16()
                if ((cls and 0x7FFF) == CLASS_IN || (cls and 0x7FFF) == 255) questions += MdnsQuestion(name, type, (cls and 0x8000) != 0)
            }
            val records = ArrayList<MdnsRecord>()
            repeat(an + ns + ar) { record()?.let { records += it } }
            return MdnsMessage((flags and FLAG_RESPONSE) != 0, id, questions, records)
        }

        /** A name at [pos], following compression pointers; leaves [pos] after the name as written in place. */
        fun name(): String {
            val out = StringBuilder()
            var p = pos
            var after = -1
            var jumps = 0
            while (true) {
                if (p >= len) throw MalformedException()
                val n = b[p].toInt() and 0xFF
                when {
                    n == 0 -> { p++; break }
                    n and 0xC0 == 0xC0 -> {
                        if (p + 2 > len) throw MalformedException()
                        val target = ((n and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                        if (after < 0) after = p + 2
                        if (++jumps > MAX_NAME_JUMPS || target >= len) throw MalformedException()
                        p = target
                    }
                    n and 0xC0 != 0 -> throw MalformedException()      // reserved label types
                    else -> {
                        if (p + 1 + n > len) throw MalformedException()
                        if (out.isNotEmpty()) out.append('.')
                        out.append(String(b, p + 1, n, Charsets.UTF_8))
                        if (out.length > MAX_NAME) throw MalformedException()
                        p += 1 + n
                    }
                }
            }
            pos = if (after >= 0) after else p
            return out.toString()
        }

        fun record(): MdnsRecord? {
            val name = name()
            val type = u16()
            val cls = u16() and 0x7FFF
            val ttl = u32()
            val rdLength = u16()
            val end = pos + rdLength
            if (end > len) throw MalformedException()
            val result: MdnsRecord? = if (cls != CLASS_IN) null else when (type) {
                TYPE_PTR -> PtrRecord(name, ttl, name())
                TYPE_SRV -> { u16(); u16(); val port = u16(); SrvRecord(name, ttl, port, name()) }
                TYPE_TXT -> TxtRecord(name, ttl, txt(end))
                TYPE_A -> if (rdLength == 4) ARecord(name, ttl, InetAddress.getByAddress(b.copyOfRange(pos, pos + 4)) as Inet4Address) else null
                else -> null
            }
            if (pos > end) throw MalformedException()
            pos = end
            return result
        }

        fun txt(end: Int): Map<String, String> {
            val map = LinkedHashMap<String, String>()
            while (pos < end) {
                val n = u8()
                if (pos + n > end) throw MalformedException()
                val s = String(b, pos, n, Charsets.UTF_8)
                pos += n
                if (s.isEmpty()) continue
                val eq = s.indexOf('=')
                if (eq < 0) map.putIfAbsent(s.lowercase(), "") else map.putIfAbsent(s.substring(0, eq).lowercase(), s.substring(eq + 1))
            }
            return map
        }
    }

    // ------------------------------------------------------------------ encode --

    fun encodeQuery(questions: List<MdnsQuestion>): ByteArray {
        val w = Writer()
        w.u16(0); w.u16(0)
        w.u16(questions.size); w.u16(0); w.u16(0); w.u16(0)
        for (q in questions) {
            w.name(q.name); w.u16(q.type); w.u16(CLASS_IN or if (q.unicast) 0x8000 else 0)
        }
        return w.toByteArray()
    }

    /** An authoritative response. [answers] are the records asked for, [additionals] come along to save a round trip. */
    fun encodeResponse(answers: List<MdnsRecord>, additionals: List<MdnsRecord> = emptyList(), id: Int = 0): ByteArray {
        val w = Writer()
        w.u16(id); w.u16(FLAG_RESPONSE or FLAG_AUTHORITATIVE)
        w.u16(0); w.u16(answers.size); w.u16(0); w.u16(additionals.size)
        for (r in answers) w.record(r)
        for (r in additionals) w.record(r)
        return w.toByteArray()
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) { u8(v shr 8); u8(v) }
        fun u32(v: Long) { u16((v shr 16).toInt()); u16(v.toInt()) }
        fun bytes(a: ByteArray) = out.write(a, 0, a.size)
        fun toByteArray(): ByteArray = out.toByteArray()

        fun name(name: String) {
            for (label in name.trimEnd('.').split('.')) {
                if (label.isEmpty()) continue
                val raw = label.toByteArray(Charsets.UTF_8)
                require(raw.size <= 63) { "label too long" }
                u8(raw.size); bytes(raw)
            }
            u8(0)
        }

        fun record(r: MdnsRecord) {
            name(r.name)
            val body = Writer()
            val type: Int
            var cls = CLASS_IN
            when (r) {
                is PtrRecord -> { type = TYPE_PTR; body.name(r.target) }
                is SrvRecord -> { type = TYPE_SRV; cls = cls or CACHE_FLUSH; body.u16(0); body.u16(0); body.u16(r.port); body.name(r.target) }
                is TxtRecord -> {
                    type = TYPE_TXT; cls = cls or CACHE_FLUSH
                    if (r.entries.isEmpty()) body.u8(0)
                    for ((k, v) in r.entries) {
                        val raw = "$k=$v".toByteArray(Charsets.UTF_8).let { if (it.size > 255) it.copyOf(255) else it }
                        body.u8(raw.size); body.bytes(raw)
                    }
                }
                is ARecord -> { type = TYPE_A; cls = cls or CACHE_FLUSH; body.bytes(r.address.address) }
            }
            u16(type); u16(cls); u32(r.ttl)
            val raw = body.toByteArray()
            u16(raw.size); bytes(raw)
        }
    }
}
