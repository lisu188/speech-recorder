package pl.lisu188.speechrecorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class LiveArchiveStorageTest {
    @Test fun livePartNamesAreDeterministicAndReversible() {
        val full = "speech_20260929_081500_000_123e4567-e89b-12d3-a456-426614174000.wav"
        val part = RecordingStorage.livePartName(full, 12)
        assertEquals(
            "__sr_live_speech_20260929_081500_000_123e4567-e89b-12d3-a456-426614174000_part0012.wav",
            part,
        )
        assertEquals(full, RecordingStorage.finalNameForLivePart(part))
        assertTrue(part.startsWith(RecordingStorage.livePrefix(full)))
    }

    @Test fun archivePolicyUsesThirtyDayCutoff() {
        val now = 1_800_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val name = "speech_20260929_081500_000_123e4567-e89b-12d3-a456-426614174000.wav"
        assertFalse(RecordingStorage.shouldArchive(name, now - 29L * day, now))
        assertTrue(RecordingStorage.shouldArchive(name, now - 30L * day, now))
        assertFalse(RecordingStorage.shouldArchive("$name.zip", now - 90L * day, now))
        assertEquals("$name.zip", RecordingStorage.archiveName(name))
    }

    @Test fun archiveCompressionIsLossless() {
        val original = ByteArray(96_000) { ((it * 31) and 0xff).toByte() }
        val packed = ByteArrayOutputStream()
        assertEquals(
            original.size.toLong(),
            RecordingStorage.writeZip(ByteArrayInputStream(original), packed, "speech_test.wav"),
        )
        val unpacked = ZipInputStream(ByteArrayInputStream(packed.toByteArray())).use { zip ->
            assertEquals("speech_test.wav", zip.nextEntry.name)
            zip.readBytes()
        }
        assertArrayEquals(original, unpacked)
    }
}
