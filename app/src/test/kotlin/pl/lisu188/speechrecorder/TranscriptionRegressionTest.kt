package pl.lisu188.speechrecorder

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class TranscriptionRegressionTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var server: HttpServer
    private lateinit var client: OpenAiClient
    private val calls = AtomicInteger()
    private var failOnCall = 0
    private var errorBody = "{\"error\":{\"code\":\"server_error\"}}"
    private var errorStatus = 503

    @Before fun setup() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/transcriptions") { request ->
            request.requestBody.use { it.readBytes() }
            val call = calls.incrementAndGet()
            val failed = call == failOnCall
            val body = (if (failed) errorBody else "{\"text\":\"fragment $call\"}").toByteArray()
            request.sendResponseHeaders(if (failed) errorStatus else 200, body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        server.start()
        client = OpenAiClient("http://127.0.0.1:${server.address.port}/transcriptions")
    }

    @After fun teardown() {
        server.stop(0)
    }

    @Test fun completedChunksSurviveNetworkFailureAndWorkerRestart() {
        val root = temporary.newFolder()
        val chunks = listOf(chunk("one"), chunk("two"), chunk("three"))
        failOnCall = 2
        assertThrows(OpenAiClient.OpenAiException::class.java) {
            client.transcribeChunks(chunks, "test-key", TranscriptionCheckpoint(root, "audio-1")) {}
        }
        val result = client.transcribeChunks(chunks, "test-key", TranscriptionCheckpoint(root, "audio-1")) {}
        assertEquals("fragment 1\n\nfragment 3\n\nfragment 4", result)
        assertEquals(4, calls.get())
    }

    @Test fun metadataFailureDoesNotUploadCompletedAudioAgain() {
        val root = temporary.newFolder()
        val checkpoint = TranscriptionCheckpoint(root, "audio-1")
        val chunks = listOf(chunk("one"), chunk("two"))
        client.transcribeChunks(chunks, "test-key", checkpoint) {}
        assertThrows(IOException::class.java) { checkpoint.remember("metadata") { throw IOException("timeout") } }
        val restored = TranscriptionCheckpoint(root, "audio-1")
        assertEquals("fragment 1\n\nfragment 2", client.transcribeChunks(chunks, "test-key", restored) {})
        assertEquals(2, calls.get())
        assertEquals("metadata", restored.remember("metadata") { "metadata" })
    }

    @Test fun emptyTranscriptIsStillCheckpointed() {
        val root = temporary.newFolder()
        TranscriptionCheckpoint(root, "audio-1").remember("chunk_0") { "" }
        assertEquals("", TranscriptionCheckpoint(root, "audio-1").remember("chunk_0") { error("Rebilled empty chunk") })
    }

    @Test fun recordingCheckpointsAreIsolated() {
        val root = temporary.newFolder()
        TranscriptionCheckpoint(root, "audio-1").remember("transcript") { "first" }
        assertEquals("second", TranscriptionCheckpoint(root, "audio-2").remember("transcript") { "second" })
        TranscriptionCheckpoint(root, "audio-1").clear()
        assertEquals("second", TranscriptionCheckpoint(root, "audio-2").remember("transcript") { error("Lost other audio") })
    }

    @Test fun budgetPausePreservesCompletedChunks() {
        val checkpoint = TranscriptionCheckpoint(temporary.newFolder(), "audio-1")
        val chunks = listOf(chunk("one"), chunk("two"))
        var remainingRequests = 1
        assertThrows(InterruptedException::class.java) {
            client.transcribeChunks(chunks, "test-key", checkpoint) {
                if (remainingRequests-- == 0) throw InterruptedException()
            }
        }
        assertEquals("fragment 1\n\nfragment 2", client.transcribeChunks(chunks, "test-key", checkpoint) {})
        assertEquals(2, calls.get())
    }

    @Test fun cancellationPreventsNewNetworkRequests() {
        client.cancel()
        assertThrows(OpenAiClient.OpenAiException::class.java) {
            client.transcribeChunks(listOf(chunk("one")), "test-key", TranscriptionCheckpoint(temporary.newFolder(), "audio-1")) {}
        }
        assertEquals(0, calls.get())
    }

    @Test fun exhaustedQuotaIsNotRetriedAndDoesNotExposeServerText() {
        failOnCall = 1
        errorStatus = 429
        errorBody = "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"sensitive token\"}}"
        val error = assertThrows(OpenAiClient.OpenAiException::class.java) {
            client.transcribeChunks(listOf(chunk("one")), "test-key", TranscriptionCheckpoint(temporary.newFolder(), "audio-1")) {}
        }
        assertFalse(error.retryable)
        assertFalse(error.message.orEmpty().contains("sensitive"))
    }

    @Test fun splitWavPreservesEverySampleAndUploadLimit() {
        val pcm = ByteArray(16000 * 2 * 60 * 8 + 320) { (it % 127).toByte() }
        val chunks = WavChunker.createChunks(ByteArrayInputStream(wav(pcm)), temporary.newFolder())
        assertEquals(2, chunks.size)
        val restored = chunks.flatMap { it.readBytes().drop(44) }.toByteArray()
        assertArrayEquals(pcm, restored)
        chunks.forEach {
            assertTrue(it.length() < 25_000_000)
            val header = ByteBuffer.wrap(it.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(it.length() - 44, header.getInt(40).toLong())
        }
    }

    @Test fun failedChunkingRemovesPartialFiles() {
        val directory = temporary.newFolder()
        val input = wav(ByteArray(100)).dropLast(2).toByteArray()
        assertThrows(IOException::class.java) { WavChunker.createChunks(ByteArrayInputStream(input), directory) }
        assertEquals(0, directory.listFiles()!!.size)
    }

    @Test fun unsupportedWavIsRejectedBeforeUpload() {
        val input = wav(ByteArray(100))
        ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).putInt(24, 48000)
        assertThrows(IOException::class.java) { WavChunker.createChunks(ByteArrayInputStream(input), temporary.newFolder()) }
        assertEquals(0, calls.get())
    }

    private fun chunk(name: String): File = temporary.newFile("$name.wav").apply { writeBytes(wav(ByteArray(320))) }

    private fun wav(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
        putInt(16).putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
        put("data".toByteArray()).putInt(pcm.size).put(pcm)
    }.array()
}
