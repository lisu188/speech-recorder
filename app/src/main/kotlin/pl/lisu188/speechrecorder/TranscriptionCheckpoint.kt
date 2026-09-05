package pl.lisu188.speechrecorder

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

class TranscriptionCheckpoint(root: File, recordingId: String) {
    private val directory = File(root, UUID.nameUUIDFromBytes(recordingId.toByteArray(Charsets.UTF_8)).toString())

    fun remember(stage: String, produce: () -> String): String {
        val target = stageFile(stage)
        if (target.isFile) return target.readText(Charsets.UTF_8)
        return produce().also { write(stage, it) }
    }

    fun write(stage: String, value: String) {
        val target = stageFile(stage)
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Unable to save transcription progress")
        val pending = File.createTempFile("checkpoint_", ".tmp", directory)
        try {
            FileOutputStream(pending).use { output ->
                output.write(value.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (!pending.renameTo(target)) throw IOException("Unable to commit transcription progress")
        } finally {
            pending.delete()
        }
    }

    fun clear() {
        directory.deleteRecursively()
    }

    fun retryAfterFailure(workId: String, maxRetries: Int): Boolean {
        val stage = "failures_$workId"
        val failures = remember(stage) { "0" }.toInt()
        write(stage, (failures + 1).toString())
        return failures < maxRetries
    }

    private fun stageFile(stage: String): File {
        require(stage.matches(Regex("[a-z0-9_-]+")))
        return File(directory, "$stage.txt")
    }
}
