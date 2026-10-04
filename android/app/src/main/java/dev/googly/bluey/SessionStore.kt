package dev.googly.bluey

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/**
 * One line in a session: something you said (overheard as context), a question you asked by holding
 * the screen, one of his replies, or a research report.
 */
data class TranscriptEntry(
    val id: String = UUID.randomUUID().toString(),
    val kind: Kind,
    val text: String,
    /** Milliseconds since 1970. */
    val time: Long,
    /** The Realtime conversation item this came from, so its transcript can be filled in when it arrives. */
    val itemID: String? = null,
) {
    @Suppress("EnumEntryName")
    enum class Kind { heard, asked, reply, report }
}

/** Everything from one wake-to-sleep session. */
data class BlueySession(
    val id: String = UUID.randomUUID().toString(),
    val started: Long,
    val ended: Long? = null,
    val entries: List<TranscriptEntry> = emptyList(),
) {
    val questionCount: Int get() = entries.count { it.kind == TranscriptEntry.Kind.asked }

    /** A short line for the session list: the first thing you asked, or the first thing you said. */
    val summary: String
        get() = (entries.firstOrNull { it.kind == TranscriptEntry.Kind.asked && it.text.isNotEmpty() }
            ?: entries.firstOrNull { it.text.isNotEmpty() })?.text ?: "Nothing said yet"

    /** In seconds. */
    val duration: Long get() = ((ended ?: System.currentTimeMillis()) - started) / 1000

    val title: String get() = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(started))

    /** Plain text, for sharing. */
    val exportText: String
        get() {
            val clock = SimpleDateFormat("h:mm:ss a", Locale.getDefault())
            val lines = mutableListOf("Bluey session, $title", "")
            for (entry in entries) {
                if (entry.text.isEmpty()) continue
                val who = when (entry.kind) {
                    TranscriptEntry.Kind.heard -> "You"
                    TranscriptEntry.Kind.asked -> "You asked"
                    TranscriptEntry.Kind.reply -> "Bluey"
                    TranscriptEntry.Kind.report -> "Research"
                }
                lines += "[${clock.format(Date(entry.time))}] $who: ${entry.text}"
            }
            return lines.joinToString("\n")
        }
}

/** Keeps every session on the phone, as one JSON file in the app's private storage. */
class SessionStore(context: Context) {
    var sessions by mutableStateOf<List<BlueySession>>(emptyList())  // newest first
        private set
    var currentID by mutableStateOf<String?>(null)
        private set

    private val file = File(context.filesDir, "sessions.json")
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val saveLater = Runnable { save() }

    init {
        sessions = try {
            if (file.exists()) decode(file.readText()) else emptyList()
        } catch (e: Exception) {
            emptyList()
        }.map { session ->
            // A session left open by a crash or force-quit is closed at its last line.
            if (session.ended == null) session.copy(ended = session.entries.lastOrNull()?.time ?: session.started) else session
        }
    }

    val current: BlueySession? get() = sessions.firstOrNull { it.id == currentID }

    fun start() {
        end()
        val session = BlueySession(started = System.currentTimeMillis())
        sessions = listOf(session) + sessions
        currentID = session.id
        save()
    }

    fun end() {
        val id = currentID ?: return
        currentID = null
        sessions = sessions.mapNotNull { session ->
            when {
                session.id != id -> session
                session.entries.all { it.text.isEmpty() } -> null  // nothing was said: don't keep an empty session
                else -> session.copy(ended = System.currentTimeMillis(), entries = session.entries.filter { it.text.isNotEmpty() })
            }
        }
        save()
    }

    /** Holds a spot for something you said, in the order you said it. The words arrive a moment later. */
    fun placeholder(itemID: String, asked: Boolean) = update { session ->
        if (session.entries.any { it.itemID == itemID }) session
        else session.copy(entries = session.entries + TranscriptEntry(kind = kind(asked), text = "",
            time = System.currentTimeMillis(), itemID = itemID))
    }

    fun heard(itemID: String, text: String, asked: Boolean) {
        val words = text.trim()
        update { session ->
            val i = session.entries.indexOfFirst { it.itemID == itemID }
            when {
                i >= 0 -> session.copy(entries = session.entries.toMutableList().also {
                    it[i] = it[i].copy(text = words, kind = if (asked) TranscriptEntry.Kind.asked else it[i].kind)
                })
                words.isNotEmpty() -> session.copy(entries = session.entries + TranscriptEntry(kind = kind(asked),
                    text = words, time = System.currentTimeMillis(), itemID = itemID))
                else -> session
            }
        }
    }

    fun reply(text: String) = append(TranscriptEntry.Kind.reply, text)

    fun report(text: String) = append(TranscriptEntry.Kind.report, text)

    fun delete(id: String) {
        if (id == currentID) currentID = null
        sessions = sessions.filter { it.id != id }
        save()
    }

    private fun append(kind: TranscriptEntry.Kind, text: String) {
        val words = text.trim()
        if (words.isEmpty()) return
        update { it.copy(entries = it.entries + TranscriptEntry(kind = kind, text = words, time = System.currentTimeMillis())) }
    }

    private fun kind(asked: Boolean) = if (asked) TranscriptEntry.Kind.asked else TranscriptEntry.Kind.heard

    private fun update(change: (BlueySession) -> BlueySession) {
        val id = currentID ?: return
        sessions = sessions.map { if (it.id == id) change(it) else it }
        main.removeCallbacks(saveLater)
        main.postDelayed(saveLater, 1000)
    }

    private fun save() {
        main.removeCallbacks(saveLater)
        val text = encode(sessions)
        io.execute {
            val temp = File(file.parentFile, "sessions.json.tmp")
            temp.writeText(text)
            temp.renameTo(file)
        }
    }

    private fun encode(list: List<BlueySession>): String {
        val array = JSONArray()
        for (s in list) {
            val entries = JSONArray()
            for (e in s.entries) {
                entries.put(JSONObject().put("id", e.id).put("kind", e.kind.name).put("text", e.text)
                    .put("time", e.time).apply { e.itemID?.let { put("itemID", it) } })
            }
            array.put(JSONObject().put("id", s.id).put("started", s.started)
                .apply { s.ended?.let { put("ended", it) } }.put("entries", entries))
        }
        return array.toString()
    }

    private fun decode(text: String): List<BlueySession> {
        val array = JSONArray(text)
        return (0 until array.length()).map { i ->
            val s = array.getJSONObject(i)
            val entries = s.getJSONArray("entries")
            BlueySession(
                id = s.getString("id"),
                started = s.getLong("started"),
                ended = if (s.has("ended")) s.getLong("ended") else null,
                entries = (0 until entries.length()).map { j ->
                    val e = entries.getJSONObject(j)
                    TranscriptEntry(
                        id = e.getString("id"),
                        kind = TranscriptEntry.Kind.valueOf(e.getString("kind")),
                        text = e.getString("text"),
                        time = e.getLong("time"),
                        itemID = if (e.has("itemID")) e.getString("itemID") else null,
                    )
                },
            )
        }
    }
}
