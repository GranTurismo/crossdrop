package com.crossdrop.core

import com.crossdrop.core.crypto.SessionIdentity
import com.crossdrop.core.protocol.FileMeta
import com.crossdrop.core.transfer.OutgoingFile
import com.crossdrop.core.transfer.ReceiveDecision
import com.crossdrop.core.transfer.TransferEngine
import com.crossdrop.core.transfer.TransferEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.util.UUID
import kotlin.random.Random

class TransferEngineTest {

    private fun engine(name: String) = TransferEngine(
        identity = SessionIdentity(),
        deviceName = { name },
        instanceId = UUID.randomUUID().toString().take(8),
    )

    private class MemorySink {
        val streams = mutableMapOf<String, ByteArrayOutputStream>()
        val factory: (FileMeta) -> ByteArrayOutputStream = { meta ->
            ByteArrayOutputStream().also { streams[meta.name] = it }
        }
    }

    @Test
    fun `loopback transfer ships bytes intact`() = runBlocking {
        val listener = ServerSocket(0)
        val endpoint = Endpoint("127.0.0.1", listener.localPort)
        val payload = Random.nextBytes(3 * 1024 * 1024 + 123) // cross several chunk frames
        val payload2 = Random.nextBytes(10)                  // second file, exercises index switch

        val sink = MemorySink()
        val serverEngine = engine("receiver")
        val serverJob = async {
            serverEngine.receive(listener.accept()) { _, offer ->
                ReceiveDecision.Accept(sink.factory)
            }.toList()
        }

        val clientEngine = engine("sender")
        val clientEvents = clientEngine.send(endpoint, listOf(
            OutgoingFile(FileMeta("a.bin", payload.size.toLong()), { ByteArrayInputStream(payload) }),
            OutgoingFile(FileMeta("b.bin", payload2.size.toLong()), { ByteArrayInputStream(payload2) }),
        )).toList()

        val serverEvents = serverJob.await()
        listener.close()

        assertArrayEquals(payload, sink.streams["a.bin"]!!.toByteArray())
        assertArrayEquals(payload2, sink.streams["b.bin"]!!.toByteArray())

        val cSas = (clientEvents.first { it is TransferEvent.Secured } as TransferEvent.Secured).sas
        val sSas = (serverEvents.first { it is TransferEvent.Secured } as TransferEvent.Secured).sas
        assertEquals(cSas, sSas)

        assertTrue(clientEvents.any { it is TransferEvent.Progress })
        assertTrue(serverEvents.any { it is TransferEvent.Progress })
        assertTrue(clientEvents.last() is TransferEvent.Completed)
        assertTrue(serverEvents.last() is TransferEvent.Completed)

        val progress = clientEvents.filterIsInstance<TransferEvent.Progress>().last()
        assertEquals((payload.size + payload2.size).toLong(), progress.bytesDone)
    }

    @Test
    fun `declined offer completes without data`() = runBlocking {
        val listener = ServerSocket(0)
        val endpoint = Endpoint("127.0.0.1", listener.localPort)
        val payload = ByteArray(1000) { 1 }

        val serverEngine = engine("receiver")
        val serverJob = async {
            serverEngine.receive(listener.accept()) { _, _ ->
                ReceiveDecision.Reject("not now")
            }.toList()
        }

        val events = engine("sender").send(endpoint, listOf(
            OutgoingFile(FileMeta("a.bin", payload.size.toLong()), { ByteArrayInputStream(payload) }),
        )).toList()

        serverJob.await()
        listener.close()

        assertTrue(events.last() is TransferEvent.DeclinedByPeer)
        assertFalse(events.any { it is TransferEvent.Progress })
    }
}
