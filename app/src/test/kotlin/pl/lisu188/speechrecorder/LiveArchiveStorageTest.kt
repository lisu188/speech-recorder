package pl.lisu188.speechrecorder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

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

    @Test fun commitReceiptHasStableNameSeparateFromLiveParts() {
        val full = "speech_20260929_081500_000_123e4567-e89b-12d3-a456-426614174000.wav"
        val marker = RecordingStorage.commitMarkerName(full)
        assertEquals(
            "commit_speech_20260929_081500_000_123e4567-e89b-12d3-a456-426614174000.ok",
            marker,
        )
        assertFalse(marker.startsWith(RecordingStorage.livePrefix(full)))
    }

    @Test fun partialOrUnknownRemoteSizeNeverCountsAsVerified() {
        assertFalse(RecordingStorage.remoteSizeMatches(65_536L, null))
        assertFalse(RecordingStorage.remoteSizeMatches(65_536L, 0L))
        assertFalse(RecordingStorage.remoteSizeMatches(65_536L, 32_768L))
        assertTrue(RecordingStorage.remoteSizeMatches(65_536L, 65_536L))
    }

    @Test fun archivePolicyUsesThirtyDayCutoff() {
        val now = 1_800_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val malformedName = "speech_legacy.wav"
        assertFalse(RecordingStorage.shouldArchive(malformedName, now - 29L * day, now))
        assertTrue(RecordingStorage.shouldArchive(malformedName, now - 30L * day, now))
        assertFalse(RecordingStorage.shouldArchive("$malformedName.zip", now - 90L * day, now))
        assertEquals("$malformedName.zip", RecordingStorage.archiveName(malformedName))
    }

    @Test fun archiveAgePrefersRecordingTimestampOverCloudModifiedTime() {
        val now = 2_000_000_000_000L
        val recentlyModified = now - 60_000L
        val oldRecording = "speech_20200101_120000_000_123e4567-e89b-12d3-a456-426614174000.wav"
        assertTrue(RecordingStorage.recordingTimestamp(oldRecording, recentlyModified) < recentlyModified)
        assertTrue(RecordingStorage.shouldArchive(oldRecording, recentlyModified, now))
    }

    @Test fun malformedLegacyNameFallsBackToCloudModifiedTime() {
        val now = 2_000_000_000_000L
        val day = 24L * 60L * 60L * 1000L
        val modified = now - 31L * day
        assertEquals(modified, RecordingStorage.recordingTimestamp("speech_legacy.wav", modified))
        assertTrue(RecordingStorage.shouldArchive("speech_legacy.wav", modified, now))
    }

    @Test fun archiveWorkRequiresCharging() {
        val constraints = RecordingStorage.archiveConstraints()
        assertTrue(constraints.requiresCharging())
        assertFalse(constraints.requiresDeviceIdle())
    }

    @Test fun archiveCriticalSectionIsSerialized() {
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val done = CountDownLatch(2)
        val active = AtomicInteger(0)
        val maximum = AtomicInteger(0)

        val first = thread(start = true, name = "archive-lock-1") {
            try {
                RecordingStorage.withArchiveLock {
                    val current = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, current) }
                    firstEntered.countDown()
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS))
                    active.decrementAndGet()
                }
            } finally {
                done.countDown()
            }
        }

        assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
        val second = thread(start = true, name = "archive-lock-2") {
            try {
                secondStarted.countDown()
                RecordingStorage.withArchiveLock {
                    val current = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, current) }
                    active.decrementAndGet()
                }
            } finally {
                done.countDown()
            }
        }

        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
        Thread.sleep(50L)
        assertEquals(1, maximum.get())
        releaseFirst.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        first.join(1000L)
        second.join(1000L)
        assertEquals(1, maximum.get())
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
