package pl.lisu188.speechrecorder

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

object WavChunker {
    private const val HEADER_SIZE = 44
    private const val SAMPLE_RATE = 16000
    private const val BYTES_PER_SAMPLE = 2
    private const val CHANNELS = 1
    private const val CHUNK_MINUTES = 8
    private const val MAX_PCM_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE * CHANNELS * 60 * CHUNK_MINUTES

    fun createChunks(context: Context, uri: Uri): List<File> {
        val directory = File(context.cacheDir, "transcription_chunks").apply { mkdirs() }
        val chunks = mutableListOf<File>()

        context.contentResolver.openInputStream(uri)?.use { rawInput ->
            BufferedInputStream(rawInput).use { input ->
                val header = ByteArray(HEADER_SIZE)
                var headerRead = 0
                while (headerRead < header.size) {
                    val read = input.read(header, headerRead, header.size - headerRead)
                    if (read < 0) throw IOException("WAV header is incomplete")
                    headerRead += read
                }
                validateHeader(header)

                var index = 0
                while (true) {
                    val file = File.createTempFile("speech_${index}_", ".wav", directory)
                    val dataBytes = writeChunk(input, file)
                    if (dataBytes == 0L) {
                        file.delete()
                        break
                    }
                    patchHeader(file, dataBytes)
                    chunks += file
                    index++
                }
            }
        } ?: throw IOException("Unable to open recording")

        if (chunks.isEmpty()) throw IOException("Recording contains no PCM data")
        return chunks
    }

    private fun validateHeader(header: ByteArray) {
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        val format = littleEndianShort(header, 20)
        val channels = littleEndianShort(header, 22)
        val sampleRate = littleEndianInt(header, 24)
        val bitsPerSample = littleEndianShort(header, 34)

        if (riff != "RIFF" || wave != "WAVE" || format != 1 || channels != CHANNELS || sampleRate != SAMPLE_RATE || bitsPerSample != 16) {
            throw IOException("Unsupported WAV format")
        }
    }

    private fun writeChunk(input: BufferedInputStream, file: File): Long {
        var written = 0L
        FileOutputStream(file).buffered().use { output ->
            output.write(ByteArray(HEADER_SIZE))
            val buffer = ByteArray(64 * 1024)
            while (written < MAX_PCM_BYTES) {
                val remaining = (MAX_PCM_BYTES - written).coerceAtMost(buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, remaining)
                if (read < 0) break
                if (read == 0) continue
                output.write(buffer, 0, read)
                written += read
            }
        }
        return written
    }

    private fun patchHeader(file: File, dataBytes: Long) {
        RandomAccessFile(file, "rw").use { output ->
            output.seek(0)
            output.writeBytes("RIFF")
            writeLeInt(output, 36L + dataBytes)
            output.writeBytes("WAVE")
            output.writeBytes("fmt ")
            writeLeInt(output, 16)
            writeLeShort(output, 1)
            writeLeShort(output, CHANNELS.toLong())
            writeLeInt(output, SAMPLE_RATE.toLong())
            writeLeInt(output, SAMPLE_RATE.toLong() * CHANNELS * BYTES_PER_SAMPLE)
            writeLeShort(output, (CHANNELS * BYTES_PER_SAMPLE).toLong())
            writeLeShort(output, 16)
            output.writeBytes("data")
            writeLeInt(output, dataBytes)
        }
    }

    private fun littleEndianShort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun littleEndianInt(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun writeLeShort(output: RandomAccessFile, value: Long) {
        output.write((value and 0xff).toInt())
        output.write((value ushr 8 and 0xff).toInt())
    }

    private fun writeLeInt(output: RandomAccessFile, value: Long) {
        output.write((value and 0xff).toInt())
        output.write((value ushr 8 and 0xff).toInt())
        output.write((value ushr 16 and 0xff).toInt())
        output.write((value ushr 24 and 0xff).toInt())
    }
}
