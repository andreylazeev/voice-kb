package ly.anoma.hex.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

internal enum class SpeechPhase { Idle, Opening, Listening, Transcribing }

private const val LOG_TAG = "HexSpeech"
private const val SAMPLE_RATE = 16_000
private const val LIVE_BATCH = SAMPLE_RATE / 5

// The IME calls start/finish/cancel on the main thread. One worker records, another transcribes,
// so a dictation can start while the one before is still transcribing (as desktop Hex queues
// them); results are inserted in the order they were spoken. Every posted result belongs to its
// original ticket, including failures.
internal class MicCapture(
    private val recognizer: Recognizer,
    private val update: (SpeechPhase, String?) -> Unit,
    private val meter: (Float) -> Unit,
    private val showPreview: (LiveSpeech?) -> Unit,
    private val insert: (String, SpeechOptions, Boolean) -> Unit,
) {
    private class Ticket(val options: SpeechOptions) {
        @Volatile var finish = false
        @Volatile var cancelled = false
        var detached = false
        var done = false
        var note: String? = null
        var text: String? = null
        val livePending = AtomicBoolean()
        // Only the transcription worker accesses the stream.
        var stream: SpeechStream? = null
        var streamAttempted = false

        fun closeStream() {
            stream?.close()
            stream = null
        }
    }
    private val main = Handler(Looper.getMainLooper())
    private val recorder = Executors.newSingleThreadExecutor { Thread(it, "Hex dictation") }
    private val transcriber = Executors.newSingleThreadExecutor { Thread(it, "Hex transcription") }
    /** The dictation holding the microphone. */
    private var active: Ticket? = null
    private var previewOwner: Ticket? = null
    var preview: LiveSpeech? = null
        private set
    /** Dictations that stopped recording, oldest first, until their text is inserted. */
    private val queue = ArrayDeque<Ticket>()
    var phase = SpeechPhase.Idle
        private set
    /** Whether a new dictation can start: nothing holds the microphone. */
    val canStart get() = active == null

    fun start(options: SpeechOptions) {
        if (active != null) return
        val ticket = Ticket(options)
        active = ticket
        publishPreview(ticket, null)
        publish(SpeechPhase.Opening)
        recorder.execute {
            try {
                record(ticket)
            } catch (_: SecurityException) {
                recordingFailed(ticket, "Allow the microphone in Hex")
            } catch (_: Exception) {
                recordingFailed(ticket, "Microphone unavailable. Try again.")
            }
        }
    }

    fun finish() {
        val ticket = active ?: return
        ticket.finish = true
        publish(SpeechPhase.Transcribing)
    }

    /** The ✕: discards the dictation recording now, else the ones still transcribing. Earlier
     * dictations still arrive when a later one is discarded while it records. */
    fun cancelLatest() {
        val ticket = active
        if (ticket != null) {
            ticket.cancelled = true
            active = null
            clearPreview(ticket)
            transcriber.execute(ticket::closeStream)
            publish(current())
        } else cancel()
    }

    /** End microphone access when the editor detaches. Keep its result for explicit insertion. */
    fun detachFromEditor() {
        queue.forEach { it.detached = true }
        publishPreview(null, null)
        active?.let {
            it.detached = true
            if (!it.finish) finish()
        }
    }

    /** Explicit cancellation discards the recording and its result. */
    fun cancel() {
        active?.let { it.cancelled = true; transcriber.execute(it::closeStream) }
        active = null
        queue.forEach { it.cancelled = true; transcriber.execute(it::closeStream) }
        queue.clear()
        publishPreview(null, null)
        publish(SpeechPhase.Idle)
    }

    fun close() { cancel(); recorder.shutdownNow(); transcriber.shutdown() }

    private fun current() = when {
        active?.finish == true -> SpeechPhase.Transcribing
        active != null -> phase.takeIf { it == SpeechPhase.Opening || it == SpeechPhase.Listening } ?: SpeechPhase.Listening
        queue.isNotEmpty() -> SpeechPhase.Transcribing
        else -> SpeechPhase.Idle
    }

    private fun publish(value: SpeechPhase, note: String? = null) {
        phase = value
        update(value, note)
    }

    private fun publishPreview(owner: Ticket?, value: LiveSpeech?) {
        previewOwner = owner
        if (preview == value) return
        preview = value
        showPreview(value)
    }

    private fun clearPreview(ticket: Ticket) {
        if (previewOwner === ticket) publishPreview(null, null)
    }

    private fun recordingFailed(ticket: Ticket, note: String) {
        main.post {
            if (!ticket.cancelled) transcriber.execute(ticket::closeStream)
            complete(ticket, note)
        }
    }

    /** A dictation ends: recording stopped with nothing to transcribe, or its text is ready. */
    private fun complete(ticket: Ticket, note: String? = null, text: String? = null) = main.post {
        if (active === ticket) active = null
        if (ticket.cancelled) { publish(current()); return@post }
        clearPreview(ticket)
        ticket.done = true; ticket.note = note; ticket.text = text
        if (ticket !in queue) queue.addLast(ticket)
        var shown: String? = null
        while (queue.firstOrNull()?.done == true) {
            val next = queue.removeFirst()
            shown = next.note ?: shown
            next.text?.let { insert(it, next.options, next.detached) }
        }
        publish(current(), shown)
    }

    /** Recording stopped with audio to transcribe: queue it behind earlier dictations. */
    private fun transcribe(ticket: Ticket, pcm: ShortArray, count: Int, submitted: Int) = main.post {
        if (active === ticket) active = null
        if (ticket.cancelled) { publish(current()); return@post }
        queue.addLast(ticket)
        publish(current())
        transcriber.execute {
            try {
                if (ticket.cancelled) return@execute
                val options = ticket.options
                val streamed = runCatching {
                    if (count > submitted) feedLive(ticket, pcm, submitted, count - submitted)
                    ticket.stream?.finish()
                }.getOrNull()
                if (ticket.cancelled) return@execute
                val raw = streamed?.takeIf { it.isNotBlank() } ?: recognizer.decode(
                    pcm, maxOf(count, SAMPLE_RATE + SAMPLE_RATE / 10), options.spoken.code, options.translate,
                    options.vocabulary.takeIf { it.isNotEmpty() }?.joinToString(", "))
                if (ticket.cancelled) return@execute
                when (val text = raw?.let { SpokenText.forDictation(it, options) }) {
                    null -> complete(ticket, "Couldn’t transcribe. Try again.")
                    "" -> complete(ticket, "No speech heard")
                    else -> complete(ticket, text = text)
                }
            } catch (_: UnsatisfiedLinkError) {
                complete(ticket, "Speech model unavailable")
            } catch (_: Exception) {
                complete(ticket, "Couldn’t transcribe. Try again.")
            } finally {
                ticket.closeStream()
            }
        }
    }

    private fun feedLive(ticket: Ticket, pcm: ShortArray, offset: Int, count: Int): LiveSpeech? {
        if (!ticket.streamAttempted) {
            ticket.streamAttempted = true
            ticket.stream = recognizer.openStream(ticket.options.spoken.code)
        }
        val stream = ticket.stream ?: return null
        val result = stream.push(pcm, offset, count)
        if (result == null) ticket.closeStream()
        return result
    }

    private fun liveBatch(ticket: Ticket, pcm: ShortArray) {
        transcriber.execute {
            try {
                if (ticket.cancelled) return@execute
                val result = feedLive(ticket, pcm, 0, pcm.size) ?: return@execute
                main.post {
                    if (active === ticket && !ticket.cancelled && !ticket.finish)
                        publishPreview(ticket, result.takeIf { it.text.isNotBlank() })
                }
            } catch (_: UnsatisfiedLinkError) {
                ticket.closeStream()
            } catch (_: Exception) {
                ticket.closeStream()
            } finally {
                ticket.livePending.set(false)
            }
        }
    }

    // No audio-focus request, playback stream, device selection or communication mode.
    // Send audio in order to the live session. No audio or transcript is written to storage.
    @SuppressLint("MissingPermission")
    private fun record(ticket: Ticket) {
        if (ticket.cancelled) return
        val rate = SAMPLE_RATE
        val block = rate / 25
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minBuffer > 0)
        val pcm = ShortArray(rate * 60)
        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            // Two seconds, so the reader can fall behind while an earlier dictation transcribes.
            .setBufferSizeInBytes(maxOf(minBuffer, rate * 2 * 2)).build()
        var count = 0
        var submitted = 0
        var voiced = 0
        var lastUpdate = 0L
        try {
            check(record.state == AudioRecord.STATE_INITIALIZED)
            if (!ticket.cancelled && !ticket.finish) {
                record.startRecording()
                check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                main.post { if (active === ticket && !ticket.finish) publish(SpeechPhase.Listening) }
            }
            while (!ticket.finish && !ticket.cancelled && count < pcm.size) {
                val n = record.read(pcm, count, minOf(block, pcm.size - count), AudioRecord.READ_BLOCKING)
                check(n > 0)
                var energy = 0.0
                for (i in count until count + n) energy += pcm[i].toDouble() * pcm[i]
                val rms = sqrt(energy / n) / 32768.0
                if (rms > .004) voiced += n
                count += n
                if (voiced >= rate / 10 && count - submitted >= LIVE_BATCH &&
                    ticket.livePending.compareAndSet(false, true)) {
                    liveBatch(ticket, pcm.copyOfRange(submitted, count))
                    submitted = count
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 75) {
                    lastUpdate = now
                    main.post { if (active === ticket && phase == SpeechPhase.Listening) meter(rms.toFloat()) }
                }
            }
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } finally { record.release() }
        }
        // Only with "setprop log.tag.HexSpeech DEBUG": how much audio arrived, never the audio or its words.
        if (Log.isLoggable(LOG_TAG, Log.DEBUG)) Log.d(LOG_TAG, "captured $count samples, $voiced voiced")
        if (ticket.cancelled) return
        if (voiced < rate / 10 || count < rate / 4) {
            recordingFailed(ticket, "No speech heard")
            return
        }
        transcribe(ticket, pcm, count, submitted)
    }
}
