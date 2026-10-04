package dev.googly.bluey

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Everything the Mac and the phone agree on (the Kotlin twin of Shared/GooglyLink.swift):
 * the Bonjour service name, what a "face update" looks like, and how lines of JSON travel over TCP.
 */
object GooglyService {
    const val TYPE = "_googly._tcp"
}

@Suppress("EnumEntryName")
enum class Mood {
    listening, resting, thinking, talking, pointing, happy, sleepy;

    companion object {
        fun from(raw: String?): Mood = entries.firstOrNull { it.name == raw } ?: listening
    }
}

/** What the phone should show right now. */
data class FaceState(
    /** Where the eyes look. x: -1 left … 1 right. y: -1 up … 1 down. */
    val gazeX: Double = 0.0,
    val gazeY: Double = 0.0,
    val mood: Mood = Mood.listening,
    /** Voice level, 0…1. Drives the talking bounce. */
    val talk: Double = 0.0,
)

data class Packet(
    val face: FaceState? = null,
    /** Sent once by each side after connecting, with a device name. */
    val hello: String? = null,
    val volume: Double? = null,
    /** A request or event, like "realtimeToken", "tool", "caption" (phone→Mac) or "wake"/"sleep" (Mac→phone). */
    val command: String? = null,
    val audio: String? = null,
    val speech: Int? = null,
    /** Pairs a request with its reply (tool calls, realtime tokens). */
    val callID: String? = null,
    /** Tool name for a "tool" request. */
    val tool: String? = null,
    /** Free text: tool arguments or output, a token, a caption. */
    val text: String? = null,
    /** A JPEG (base64) that goes with a tool result. */
    val image: String? = null,
) {
    /** Missing values are left out, exactly like Swift's Codable does, so the Mac decodes it happily. */
    fun toJson(): String {
        val json = JSONObject()
        face?.let {
            json.put("face", JSONObject()
                .put("gazeX", it.gazeX).put("gazeY", it.gazeY).put("mood", it.mood.name).put("talk", it.talk))
        }
        hello?.let { json.put("hello", it) }
        volume?.let { json.put("volume", it) }
        command?.let { json.put("command", it) }
        audio?.let { json.put("audio", it) }
        speech?.let { json.put("speech", it) }
        callID?.let { json.put("callID", it) }
        tool?.let { json.put("tool", it) }
        text?.let { json.put("text", it) }
        image?.let { json.put("image", it) }
        return json.toString()
    }

    companion object {
        fun parse(line: String): Packet? = try {
            val json = JSONObject(line)
            Packet(
                face = json.optJSONObject("face")?.let {
                    FaceState(it.optDouble("gazeX", 0.0), it.optDouble("gazeY", 0.0),
                        Mood.from(it.optString("mood")), it.optDouble("talk", 0.0))
                },
                hello = json.str("hello"),
                volume = if (json.has("volume") && !json.isNull("volume")) json.getDouble("volume") else null,
                command = json.str("command"),
                audio = json.str("audio"),
                speech = if (json.has("speech") && !json.isNull("speech")) json.getInt("speech") else null,
                callID = json.str("callID"),
                tool = json.str("tool"),
                text = json.str("text"),
                image = json.str("image"),
            )
        } catch (e: Exception) {
            null
        }

        private fun JSONObject.str(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
    }
}

/**
 * Newline-delimited JSON over one TCP socket. Reading happens on its own thread and writing on another;
 * every callback runs on the main thread. [onClosed] fires exactly once, however the connection ends.
 */
class LineConnection(private val host: InetAddress, private val port: Int) {
    var onReady: (() -> Unit)? = null
    var onPacket: ((Packet) -> Unit)? = null
    var onClosed: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var socket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var cancelled = false

    fun start() {
        thread(name = "googly-link", isDaemon = true) { run() }
    }

    private fun run() {
        val socket = Socket()
        this.socket = socket
        try {
            if (cancelled) return
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(host, port), 5000)
            output = BufferedOutputStream(socket.getOutputStream())
            main.post { if (!cancelled) onReady?.invoke() }
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
            while (!cancelled) {
                val line = reader.readLine() ?: break
                val packet = Packet.parse(line) ?: continue
                main.post { if (!cancelled) onPacket?.invoke(packet) }
            }
        } catch (e: IOException) {
            if (!cancelled) Log.i("Googly", "Mac link closed: ${e.message}")
        } finally {
            try { socket.close() } catch (_: IOException) {}
            writer.shutdown()
            main.post { onClosed?.invoke() }
        }
    }

    fun send(packet: Packet) {
        val bytes = (packet.toJson() + "\n").toByteArray(Charsets.UTF_8)
        try {
            writer.execute {
                val out = output ?: return@execute
                try {
                    out.write(bytes)
                    out.flush()
                } catch (e: IOException) {
                    try { socket?.close() } catch (_: IOException) {}
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Already closed.
        }
    }

    fun cancel() {
        cancelled = true
        val socket = socket
        thread(isDaemon = true) { try { socket?.close() } catch (_: IOException) {} }
    }
}
