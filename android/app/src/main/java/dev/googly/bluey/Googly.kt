package dev.googly.bluey

import android.content.Context

/** Owns the pieces and connects the live voice to the Mac: keys, tools, captions and wake/sleep. */
class Googly(context: Context) {
    val link = MacLink(context)
    val live = LiveVoice(context)
    val store = SessionStore(context)
    val animator = FaceAnimator()

    init {
        link.onFace = { animator.receive(it) }
        animator.localTalk = { live.level }

        live.requestSession = { done ->
            link.request(Packet(command = "realtimeToken")) { reply -> done(reply) }
        }
        live.tellMac = { link.send(it) }
        live.runTool = { name, arguments, done ->
            link.request(Packet(command = "tool", tool = name, text = arguments)) { reply ->
                done(reply?.text ?: "The Mac didn't answer.", reply?.image)
            }
        }
        live.onSessionStart = { store.start() }
        live.onSessionEnd = { store.end() }
        live.onUserTurn = { item, asked -> store.placeholder(item, asked) }
        live.onUserWords = { item, text, asked -> store.heard(item, text, asked) }
        live.onReply = { store.reply(it) }
        live.onReport = { store.report(it) }
        live.onCaption = { text, finished ->
            link.send(Packet(command = if (finished) "captionDone" else "caption", text = text))
        }
        live.onStateChange = { state ->
            when (state) {
                LiveVoice.State.Asleep -> {
                    animator.awake = false
                    animator.localMood = null
                    link.send(Packet(command = "asleep"))
                }
                LiveVoice.State.Waking -> {
                    animator.awake = true
                    animator.localMood = Mood.happy
                }
                LiveVoice.State.Listening -> {
                    animator.awake = true
                    animator.localMood = null
                    link.send(Packet(command = "awake"))
                }
                LiveVoice.State.Asking -> {
                    animator.awake = true
                    animator.localMood = Mood.listening  // all ears while you hold
                    link.send(Packet(command = "awake"))
                }
                LiveVoice.State.Thinking -> animator.localMood = Mood.thinking
                LiveVoice.State.Speaking -> animator.localMood = Mood.talking
            }
        }
        // When the Mac is the brain, its session is gone with the link: nap, so a double tap starts a fresh one.
        link.onDisconnected = { if (live.usesMac) live.sleep() }
        link.onCommand = { packet ->
            when (packet.command) {
                "wake" -> live.wake()
                "sleep" -> live.sleep()
                else -> live.fromMac(packet)
            }
        }
    }
}
