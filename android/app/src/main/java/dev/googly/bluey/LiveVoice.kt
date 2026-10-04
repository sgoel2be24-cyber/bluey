package dev.googly.bluey

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A live session with OpenAI's Realtime API, running on the phone. Once he's awake the mic is on the whole
 * time, so everything you say becomes context, but he stays quiet until you hold the screen and ask.
 * He never talks out loud: replies are text (speech bubbles on the Mac) with a little cartoon chirp here.
 * Tools (look at the screen, point, use the computer) run on the Mac.
 */
class LiveVoice(context: Context) {
    enum class State { Asleep, Waking, Listening, Asking, Thinking, Speaking }

    companion object {
        const val MODEL = "gpt-realtime-2.1"
        private const val RATE = 24000  // what the Realtime API wants: 24 kHz mono 16-bit PCM
        private const val TAG = "Googly"
    }

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("googly", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    var state by mutableStateOf(State.Asleep)
        private set

    /**
     * Asks the Mac how to start: its reply carries a short-lived OpenAI key, or says "macBrain" when the Mac
     * listens and thinks itself (Fireworks). Calls back with null if the Mac couldn't start a session.
     */
    var requestSession: ((done: (Packet?) -> Unit) -> Unit)? = null
    /** Sends an event to the Mac (hold and let go, when the Mac is the brain). */
    var tellMac: ((Packet) -> Unit)? = null
    /** Runs a tool on the Mac: (name, JSON arguments) → (output text, optional JPEG base64). */
    var runTool: ((name: String, arguments: String, done: (String, String?) -> Unit) -> Unit)? = null
    /** What he's saying, as it streams in. The flag is true when the reply finished. */
    var onCaption: ((String, Boolean) -> Unit)? = null
    var onStateChange: ((State) -> Unit)? = null
    /** For the saved transcript: a session started or ended, something you said (in order, then its words), his replies. */
    var onSessionStart: (() -> Unit)? = null
    var onSessionEnd: (() -> Unit)? = null
    var onUserTurn: ((itemID: String, asked: Boolean) -> Unit)? = null
    var onUserWords: ((itemID: String, text: String, asked: Boolean) -> Unit)? = null
    var onReply: ((String) -> Unit)? = null
    var onReport: ((String) -> Unit)? = null
    /** Shows Android's microphone prompt. Set by the activity. */
    var askMicPermission: ((granted: (Boolean) -> Unit) -> Unit)? = null

    /** Loudness of his chirp right now, 0…1. */
    @Volatile var level = 0.0
        private set

    private var volumeState by mutableDoubleStateOf(prefs.getFloat("volume", 1f).toDouble())
    var volume: Double
        get() = volumeState
        set(value) {
            volumeState = value
            prefs.edit().putFloat("volume", value.toFloat()).apply()
            track?.setVolume(value.toFloat())
        }

    @Volatile private var socket: WebSocket? = null
    /** The Mac does the listening and thinking (Fireworks); this phone is the face, the hold button and the chirps. */
    private var macBrain = false
    private val live get() = socket != null || macBrain
    private var recorder: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var recording = false
    private var track: AudioTrack? = null
    private val effects = mutableListOf<AudioEffect>()
    private val chirper = Executors.newSingleThreadExecutor()
    private var audioReady = false

    private var transcript = ""
    private var pendingBuffers = 0
    private var responseActive = false
    private var sleepAfterReply = false
    /** Holding the screen to ask. Released before the session was ready: ask as soon as it is. */
    private var holding = false
    private var askWhenReady = false
    private var chirpedThisResponse = false
    /** Turns that were questions (said while holding, or committed when you let go). */
    private val askedItems = mutableSetOf<String>()
    private var awaitingQuestion = false
    private var toolsRunning = 0

    private fun moveTo(new: State) {
        if (new == state) return
        state = new
        onStateChange?.invoke(new)
    }

    // region Wake and sleep

    fun toggle() {
        if (state == State.Asleep) wake() else sleep()
    }

    fun wake() {
        if (state != State.Asleep) return
        moveTo(State.Waking)
        val requestSession = requestSession ?: run { moveTo(State.Asleep); return }
        requestSession { reply ->
            main.post {
                if (state != State.Waking) return@post
                val token = reply?.text
                when {
                    reply?.command == "macBrain" -> startMacBrain()
                    token != null -> withMic { granted ->
                        if (state != State.Waking) return@withMic
                        if (granted) {
                            connect(token)
                        } else {
                            onCaption?.invoke("I need the microphone. Turn it on for Googly Eyes in your phone's Settings.", true)
                            moveTo(State.Asleep)
                        }
                    }
                    else -> moveTo(State.Asleep)
                }
            }
        }
    }

    private fun withMic(then: (Boolean) -> Unit) {
        when {
            hasMic() -> then(true)
            askMicPermission != null -> askMicPermission?.invoke { granted -> main.post { then(granted) } }
            else -> then(false)
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun sleep() {
        if (live) onSessionEnd?.invoke()
        macBrain = false
        askedItems.clear()
        awaitingQuestion = false
        val old = socket
        socket = null
        old?.close(1000, null)
        stopAudio()
        transcript = ""
        pendingBuffers = 0
        responseActive = false
        sleepAfterReply = false
        holding = false
        askWhenReady = false
        toolsRunning = 0
        level = 0.0
        moveTo(State.Asleep)
    }

    /** Says a quick hi (for checking the chirp volume). */
    fun sayHi() {
        if (!live) {
            wake()
            return
        }
        if (macBrain) {
            tellMac?.invoke(Packet(command = "sayHi"))
            return
        }
        send(JSONObject().put("type", "response.create")
            .put("response", JSONObject().put("instructions", "Reply with a quick, cheerful hi in under eight words.")))
    }

    // endregion

    // region Hold to ask

    /** You pressed and held the screen: what you say now is the question. */
    fun beginAsk() {
        holding = true
        onCaption?.invoke("", false)  // clears his last bubble
        if (state == State.Asleep) {
            wake()
            return
        }
        if (!live) return
        if (state == State.Listening || state == State.Speaking) moveTo(State.Asking)
        if (macBrain) tellMac?.invoke(Packet(command = "askStart"))
    }

    /** You let go: he answers (or does the thing) using everything he's heard as context. */
    fun endAsk() {
        if (!holding) return
        holding = false
        if (!live || state == State.Waking) {
            askWhenReady = true
            return
        }
        ask()
    }

    private fun ask() {
        askWhenReady = false
        moveTo(State.Thinking)
        awaitingQuestion = true
        if (macBrain) {
            responseActive = true  // until the Mac says it's done
            tellMac?.invoke(Packet(command = "askEnd"))
            return
        }
        // Close off what you just said (it may still be mid-sentence) and ask for a reply.
        send(JSONObject().put("type", "input_audio_buffer.commit"))
        send(JSONObject().put("type", "response.create"))
    }

    // endregion

    // region Connection

    private fun connect(token: String) {
        val request = Request.Builder()
            .url("wss://api.openai.com/v1/realtime?model=$MODEL")
            .header("Authorization", "Bearer $token")
            .build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = try { JSONObject(text) } catch (e: Exception) { return }
                main.post { if (webSocket === socket) handle(event) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Realtime closing: $code $reason")
                webSocket.close(1000, null)
                main.post { if (webSocket === socket) sleep() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Realtime closed: $t ${response?.code ?: ""}")
                main.post { if (webSocket === socket) sleep() }
            }
        })
        socket = ws
        try {
            startSpeaker()
            startMic()
        } catch (e: Exception) {
            onCaption?.invoke("I couldn't start the microphone: ${e.message}", true)
            sleep()
            return
        }
        onSessionStart?.invoke()
        chirp(2)
        if (askWhenReady) ask() else moveTo(if (holding) State.Asking else State.Listening)
    }

    private fun send(event: JSONObject) {
        socket?.send(event.toString())
    }

    /** The Mac is the brain: no OpenAI session or mic here, just the speaker for his chirps. */
    private fun startMacBrain() {
        macBrain = true
        try {
            startSpeaker()
        } catch (e: Exception) {
            Log.w(TAG, "No speaker for chirps: $e")
        }
        onSessionStart?.invoke()
        chirp(2)
        if (askWhenReady) {
            ask()
        } else {
            moveTo(if (holding) State.Asking else State.Listening)
            if (holding) tellMac?.invoke(Packet(command = "askStart"))
        }
    }

    /** What the Mac heard and said, when it's the brain. */
    fun fromMac(packet: Packet) {
        if (!macBrain) return
        val text = packet.text.orEmpty()
        val item = "mac-${packet.speech ?: 0}"
        when (packet.command) {
            "heard" -> {
                onUserTurn?.invoke(item, false)
                onUserWords?.invoke(item, text, false)
            }
            "asked" -> {
                onUserTurn?.invoke(item, true)
                onUserWords?.invoke(item, text, true)
            }
            "replying" -> {
                moveTo(State.Speaking)
                chirp(text.trim().split(Regex("\\s+")).size.coerceIn(3, 5))
            }
            "replyDone" -> if (text.isNotBlank()) onReply?.invoke(text)
            "report" -> if (text.isNotBlank()) onReport?.invoke(text)
            "turnDone" -> {
                responseActive = false
                awaitingQuestion = false
                finishIfQuiet()
            }
        }
    }

    // endregion

    // region Events

    private fun handle(event: JSONObject) {
        when (event.optString("type")) {
            "response.created" -> {
                responseActive = true
                transcript = ""
                chirpedThisResponse = false
            }

            "response.output_text.delta", "response.output_audio_transcript.delta" -> {
                val delta = event.optString("delta")
                transcript += delta
                if (!chirpedThisResponse && transcript.isNotBlank()) {
                    chirpedThisResponse = true
                    moveTo(State.Speaking)
                    chirp(transcript.trim().split(Regex("\\s+")).size.coerceIn(3, 5))
                }
                onCaption?.invoke(transcript, false)
            }

            "input_audio_buffer.committed" -> {
                val item = event.optString("item_id").takeIf { it.isNotEmpty() } ?: return
                val asked = holding || awaitingQuestion
                if (asked) askedItems += item
                if (awaitingQuestion && !holding) awaitingQuestion = false
                onUserTurn?.invoke(item, asked)
            }

            "conversation.item.input_audio_transcription.completed" -> {
                val item = event.optString("item_id").takeIf { it.isNotEmpty() } ?: return
                onUserWords?.invoke(item, event.optString("transcript"), item in askedItems)
            }

            "response.done" -> {
                responseActive = false
                if (transcript.isNotEmpty()) {
                    onCaption?.invoke(transcript, true)
                    onReply?.invoke(transcript)
                }
                val output = event.optJSONObject("response")?.optJSONArray("output") ?: JSONArray()
                val calls = (0 until output.length()).mapNotNull { output.optJSONObject(it) }
                    .filter { it.optString("type") == "function_call" }
                if (calls.isNotEmpty()) {
                    if (state != State.Speaking) moveTo(State.Thinking)
                    runTools(calls)
                }
                finishIfQuiet()
            }

            "error" -> {
                val error = event.optJSONObject("error")
                if (error?.optString("code") == "input_audio_buffer_commit_empty") awaitingQuestion = false
                Log.w(TAG, "Realtime error: ${error?.optString("message") ?: "Something went wrong."}")
            }
        }
    }

    private fun runTools(calls: List<JSONObject>) {
        var remaining = calls.size
        toolsRunning += 1
        val images = mutableListOf<String>()
        for (call in calls) {
            val name = call.optString("name")
            val callID = call.optString("call_id")
            val arguments = call.optString("arguments", "{}")
            if (name == "go_to_sleep") sleepAfterReply = true
            val finish: (String, String?) -> Unit = { output, image ->
                main.post finish@{
                    if (socket == null) return@finish
                    if (name == "web_research") {
                        val marker = "\n---REPORT---\n"
                        val at = output.indexOf(marker)
                        if (at >= 0) onReport?.invoke(output.substring(at + marker.length))
                    }
                    send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                        .put("type", "function_call_output").put("call_id", callID).put("output", output)))
                    if (image != null) images += image
                    remaining -= 1
                    if (remaining == 0) {
                        toolsRunning = max(0, toolsRunning - 1)
                        // Screenshots go in as a user image so he can actually see the screen.
                        for (jpeg in images) {
                            send(JSONObject().put("type", "conversation.item.create").put("item", JSONObject()
                                .put("type", "message").put("role", "user")
                                .put("content", JSONArray().put(JSONObject()
                                    .put("type", "input_image").put("image_url", "data:image/jpeg;base64,$jpeg")))))
                        }
                        send(JSONObject().put("type", "response.create"))
                    }
                }
            }
            runTool?.invoke(name, arguments, finish) ?: finish("The Mac isn't connected.", null)
        }
    }

    /** Back to listening once he's replied and chirped (or asleep, if he said goodbye). */
    private fun finishIfQuiet() {
        if (pendingBuffers != 0 || responseActive || toolsRunning != 0) return
        if (sleepAfterReply) {
            sleep()
            return
        }
        if (state == State.Speaking || state == State.Thinking) moveTo(if (holding) State.Asking else State.Listening)
    }

    // endregion

    // region Audio

    private fun startSpeaker() {
        val minOut = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val out = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(max(minOut, RATE / 10 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        out.setVolume(volume.toFloat())
        out.play()
        track = out
        audioReady = true
    }

    @SuppressLint("MissingPermission")
    private fun startMic() {
        val (rec, rate) = openRecorder() ?: throw IllegalStateException("no microphone is available")
        recorder = rec
        // Echo cancellation, so he doesn't hear his own chirps.
        if (AcousticEchoCanceler.isAvailable()) {
            AcousticEchoCanceler.create(rec.audioSessionId)?.let { it.enabled = true; effects += it }
        }
        if (NoiseSuppressor.isAvailable()) {
            NoiseSuppressor.create(rec.audioSessionId)?.let { it.enabled = true; effects += it }
        }

        rec.startRecording()
        recording = true
        micThread = thread(name = "googly-mic", isDaemon = true) { micLoop(rec, rate) }
    }

    /** The voice-call mic (with the phone's own echo cancelling), at 24 kHz if it can, otherwise resampled. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): Pair<AudioRecord, Int>? {
        for (rate in intArrayOf(RATE, 48000, 16000, 44100)) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) continue
            val rec = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, max(min, rate / 5 * 2))
            } catch (e: Exception) {
                continue
            }
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to rate
            rec.release()
        }
        return null
    }

    private fun micLoop(rec: AudioRecord, rate: Int) {
        val chunk = ShortArray(rate / 10)  // 100 ms
        while (recording) {
            val n = rec.read(chunk, 0, chunk.size)
            if (n <= 0) {
                if (n < 0) break
                continue
            }
            val samples = if (rate == RATE) chunk.copyOf(n) else resample(chunk, n, rate)
            val bytes = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val s = samples[i].toInt()
                bytes[2 * i] = (s and 0xFF).toByte()
                bytes[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
            }
            val audio = Base64.encodeToString(bytes, Base64.NO_WRAP)
            socket?.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"$audio\"}")
        }
    }

    /** Linear resampling to 24 kHz. */
    private fun resample(input: ShortArray, count: Int, from: Int): ShortArray {
        val outCount = (count.toLong() * RATE / from).toInt()
        val out = ShortArray(outCount)
        val step = from.toDouble() / RATE
        for (i in 0 until outCount) {
            val pos = i * step
            val a = pos.toInt().coerceAtMost(count - 1)
            val b = (a + 1).coerceAtMost(count - 1)
            val t = pos - a
            out[i] = (input[a] * (1 - t) + input[b] * t).toInt().toShort()
        }
        return out
    }

    private fun stopAudio() {
        recording = false
        micThread?.join(300)
        micThread = null
        recorder?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        recorder = null
        effects.forEach { it.release() }
        effects.clear()
        audioReady = false
        val old = track
        track = null
        // Let any chirp that's mid-flight finish writing before the speaker is let go.
        chirper.execute {
            try {
                old?.pause()
                old?.flush()
            } catch (_: Exception) {}
            old?.release()
        }
    }

    /**
     * A tiny cartoon chirp: a few soft, round, bell-like notes from a happy pentatonic scale,
     * each with a little upward "boop" at the start, like a small creature humming.
     */
    private fun chirp(syllables: Int) {
        val out = track
        if (!audioReady || out == null) return
        val rate = RATE.toDouble()
        // C major pentatonic, two octaves up high (C6…E7), so it always sounds sweet together.
        val scale = doubleArrayOf(1046.5, 1174.7, 1318.5, 1568.0, 1760.0, 2093.0, 2349.3, 2637.0)
        var index = Random.nextInt(1, 4)
        val samples = ArrayList<Float>()
        for (i in 0 until syllables) {
            if (i > 0) index = (index + listOf(-1, 1, 1, 2).random()).coerceIn(0, scale.size - 1)
            val note = scale[index] * 0.5  // drop an octave: rounder, less piercing
            val duration = if (i == syllables - 1) 0.13 else Random.nextDouble(0.07, 0.09)
            val count = (duration * rate).toInt()
            var phase = 0.0
            for (n in 0 until count) {
                val time = n / rate
                val t = n.toDouble() / count
                // Scoops up into the note over the first 25 ms, with a gentle wobble on the last one.
                val scoop = 1 - 0.18 * exp(-time / 0.012)
                val wobble = if (i == syllables - 1) 1 + 0.012 * sin(time * 2 * PI * 18) else 1.0
                phase += 2 * PI * note * scoop * wobble / rate
                val attack = min(1.0, time / 0.006)
                val envelope = attack * exp(-t * 3.2) * (1 - t.pow(6))
                val wave = sin(phase) + 0.12 * sin(2 * phase)
                samples += (wave * envelope * 0.26).toFloat()
            }
            repeat((rate * 0.028).toInt()) { samples += 0f }
        }
        pendingBuffers += 1
        chirper.execute {
            play(out, samples)
            main.post {
                pendingBuffers = max(0, pendingBuffers - 1)
                if (pendingBuffers == 0) level = 0.0
                finishIfQuiet()
            }
        }
    }

    /** Writes the chirp to the speaker in small pieces, keeping [level] in step with what's playing. */
    private fun play(out: AudioTrack, samples: List<Float>) {
        try {
            val piece = 1024
            val shorts = ShortArray(piece)
            var start = 0
            while (start < samples.size) {
                val n = min(piece, samples.size - start)
                var sum = 0f
                for (i in 0 until n) {
                    val s = samples[start + i]
                    sum += s * s
                    shorts[i] = (s.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
                }
                level = min(1.0, sqrt(sum / n).toDouble() * 5)
                if (out.write(shorts, 0, n) < 0) return
                start += n
            }
            // write() returns once the last piece is queued; wait for it to actually come out.
            Thread.sleep((out.bufferSizeInFrames * 1000L / RATE).coerceIn(20, 250))
        } catch (e: Exception) {
            // The speaker was released mid-chirp (he went to sleep).
        }
    }

    // endregion
}
