package org.veejr.android

import android.content.Context
import android.media.MediaRecorder
import java.io.File

class VoiceRecorder(context: Context) {
    private val recordingDirectory = File(context.cacheDir, "voice-recordings")
    private var recorder: MediaRecorder? = null
    private var output: File? = null

    fun start() {
        check(recorder == null) { "A recording is already in progress." }
        recordingDirectory.mkdirs()
        val file = File(recordingDirectory, "voice-${System.currentTimeMillis()}.m4a")

        val newRecorder = MediaRecorder()
        try {
            newRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
        } catch (error: Exception) {
            newRecorder.release()
            file.delete()
            throw error
        }

        recorder = newRecorder
        output = file
    }

    fun stop(): File {
        val activeRecorder = checkNotNull(recorder) { "No recording is in progress." }
        val file = checkNotNull(output)

        var saved = false
        try {
            activeRecorder.stop()
            check(file.length() > 0) { "The recording is empty." }
            saved = true
            return file
        } finally {
            activeRecorder.reset()
            activeRecorder.release()
            recorder = null
            output = null
            if (!saved) file.delete()
        }
    }

    fun cancel() {
        val activeRecorder = recorder
        val file = output
        if (activeRecorder != null) {
            runCatching { activeRecorder.stop() }
            activeRecorder.reset()
            activeRecorder.release()
        }
        file?.delete()
        recorder = null
        output = null
    }
}
