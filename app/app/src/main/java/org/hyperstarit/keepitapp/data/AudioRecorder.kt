package org.hyperstarit.keepitapp.data

import android.content.Context
import android.media.MediaRecorder
import java.io.File

/**
 * Records a voice note to an m4a file.
 *
 * <h3>Why these settings</h3>
 * **Mono, 22.05 kHz, AAC at ~32 kbps.** A phone's microphone array produces one channel after its
 * own noise suppression, so stereo would store two copies of the same voice. 22 kHz is chosen over
 * the 16 kHz speech-to-text engines actually consume because those engines downsample anyway —
 * recording higher costs transcription nothing and only costs bytes, while 16 kHz is audibly
 * closed-in when a person plays their own note back. At this bitrate the server's 10 MB attachment
 * cap is roughly 40 minutes of speech; at 44.1 kHz stereo it would be about ten.
 *
 * **AAC in MPEG-4, not Opus in Ogg**, even though Opus is the better codec per bit and the minSdk
 * supports it: a recording made here is played back in the web app too, and Safari's Ogg support
 * cannot be relied on. Being playable everywhere beats being smaller.
 *
 * The recorder stops itself before it can produce a file the server would refuse — see
 * [MAX_FILE_BYTES] — so a long recording ends with something that uploads rather than a 413.
 */
class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var target: File? = null

    /** True while a recording is in progress. */
    val isRecording: Boolean get() = recorder != null

    /**
     * Starts recording into a new file under the app's cache.
     *
     * @param onLimitReached invoked on the recorder's own thread when a limit stopped it early.
     * @return the file being written, or null when the recorder could not be started at all
     *   (microphone busy, permission missing, a device that refuses the configuration).
     */
    fun start(onLimitReached: () -> Unit): File? {
        if (recorder != null) return null

        val file = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        val media = MediaRecorder(context)

        return runCatching {
            media.apply {
                // MIC rather than VOICE_RECOGNITION: the latter switches off the processing that
                // makes a recording pleasant to listen to, and these are played back by people.
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioChannels(1)
                setAudioSamplingRate(SAMPLE_RATE_HZ)
                setAudioEncodingBitRate(BIT_RATE)
                setMaxFileSize(MAX_FILE_BYTES)
                setMaxDuration(MAX_DURATION_MS)
                setOutputFile(file.absolutePath)
                setOnInfoListener { _, what, _ ->
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED ||
                        what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
                    ) {
                        onLimitReached()
                    }
                }
                prepare()
                start()
            }
            recorder = media
            target = file
            file
        }.getOrElse {
            runCatching { media.release() }
            file.delete()
            null
        }
    }

    /**
     * Stops recording and returns the finished file.
     *
     * A recording stopped within a moment of starting has no frames, and `MediaRecorder.stop()`
     * throws rather than writing an unplayable file — treated as "no recording", not an error.
     *
     * @return the file, or null when nothing usable was captured.
     */
    fun stop(): File? {
        val media = recorder ?: return null
        val file = target
        recorder = null
        target = null

        val stopped = runCatching { media.stop() }.isSuccess
        runCatching { media.release() }

        if (!stopped || file == null || !file.isFile || file.length() == 0L) {
            file?.delete()
            return null
        }
        return file
    }

    /** Stops and discards whatever was being recorded. */
    fun cancel() {
        stop()?.delete()
    }

    /**
     * The loudest sample since the last call, 0..1 — enough for a level meter that shows the
     * microphone is actually hearing something, which is the one thing a timer cannot tell you.
     */
    fun level(): Float {
        val amplitude = runCatching { recorder?.maxAmplitude ?: 0 }.getOrDefault(0)
        return (amplitude / MAX_AMPLITUDE).coerceIn(0f, 1f)
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 22_050
        const val BIT_RATE = 32_000

        /**
         * Just under the server's 10 MB attachment cap, so the recorder stops on its own terms
         * rather than the upload being refused after the fact.
         */
        const val MAX_FILE_BYTES = 9L * 1024 * 1024

        /** An hour, as a backstop for a recording left running by accident. */
        const val MAX_DURATION_MS = 60 * 60 * 1000

        /** MediaRecorder reports amplitude on a 16-bit scale. */
        const val MAX_AMPLITUDE = 32_767f
    }
}
