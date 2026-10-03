package com.crossdrop.core.protocol

import com.crossdrop.core.crypto.RecordCipher
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire frame types. HELLO/HELLO_ACK are cleartext; everything after them is AEAD-sealed. */
enum class FrameType(val code: Int) {
    HELLO(0), HELLO_ACK(1), OFFER(2), ACCEPT(3), DECLINE(4),
    DATA(5), DONE(6), CANCEL(7), ERROR(8);

    companion object {
        private val byCode = entries.associateBy { it.code }
        fun from(code: Int) = byCode[code] ?: throw ProtocolException("unknown frame type $code")
    }
}

class ProtocolException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Frame layout: `[u32 BE bodyLen][body]` where body = `[u8 type][payload]` —
 * or, once a session is established, `seal(type || payload)`. Length covers the
 * (possibly sealed) body only.
 */
class FrameIO(
    private val input: InputStream,
    private val output: OutputStream,
) {
    var readCipher: RecordCipher? = null
    var writeCipher: RecordCipher? = null

    private val header = ByteArray(4)

    fun write(type: FrameType, payload: ByteArray = ByteArray(0)) {
        var body = ByteArray(1 + payload.size)
        body[0] = type.code.toByte()
        payload.copyInto(body, 1)
        writeCipher?.let { body = it.seal(body) }
        require(body.size <= MAX_FRAME) { "frame too large: ${body.size}" }
        val hdr = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(body.size).array()
        synchronized(output) {
            output.write(hdr)
            output.write(body)
            output.flush()
        }
    }

    /** Blocks until a full frame arrives. Throws [EOFException] on clean close. */
    fun read(): Pair<FrameType, ByteArray> {
        readFully(header)
        val len = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        if (len <= 0 || len > MAX_FRAME) throw ProtocolException("bad frame length $len")
        var body = ByteArray(len)
        readFully(body)
        readCipher?.let { body = it.open(body) }
        if (body.isEmpty()) throw ProtocolException("empty frame body")
        return FrameType.from(body[0].toInt() and 0xFF) to body.copyOfRange(1, body.size)
    }

    private fun readFully(buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) throw EOFException("stream closed")
            off += n
        }
    }

    companion object {
        const val MAX_FRAME = 4 * 1024 * 1024
    }
}
