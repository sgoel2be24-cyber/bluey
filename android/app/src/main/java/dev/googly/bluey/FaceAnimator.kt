package dev.googly.bluey

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A tiny damped spring, so every part of the face moves with a little life. */
private class Spring(var value: Double, val stiffness: Double = 200.0, val damping: Double = 0.7) {
    var velocity = 0.0

    fun step(target: Double, dt: Double) {
        val c = 2 * sqrt(stiffness) * damping
        velocity += (stiffness * (target - value) - c * velocity) * dt
        value += velocity * dt
    }
}

private fun rand(from: Double, to: Double) = Random.nextDouble(from, to)

/** Smooths what the Mac asks for into lifelike motion: darting eyes, brows, blinks, leaning, hops. */
class FaceAnimator {
    class Frame(
        val gazeX: Double,
        val gazeY: Double,
        val mood: Mood,
        val talk: Double,
        val closed: Double,      // 0 open … 1 shut (blink)
        val breathe: Double,
        val time: Double,
        val pupil: Double,       // pupil size multiplier
        val browLift: Double,    // + raises the brows (in design px)
        val browTilt: Double,    // + worried/curious, - focused (radians)
        val lean: Double,        // head tilt toward where he's looking (radians)
        val hop: Double,         // little idle hop (design px, up is +)
        val blush: Double,       // 0…1
        val squint: Double,      // 0…1 happy squint from below
    )

    private var target = FaceState()
    private var lastPacket = -1e9
    private val gazeX = Spring(0.0, 260.0, 0.82)
    private val gazeY = Spring(0.0, 260.0, 0.82)
    private val pupil = Spring(1.0, 160.0, 0.5)
    private val browLift = Spring(0.0, 180.0, 0.55)
    private val browTilt = Spring(0.0, 150.0, 0.6)
    private val lean = Spring(0.0, 60.0, 0.8)
    private val hop = Spring(0.0, 260.0, 0.35)
    private val blush = Spring(0.25, 40.0, 1.0)
    private val squint = Spring(0.0, 150.0, 0.7)
    private var talk = 0.0
    private var mood = Mood.listening
    private var blinkStart = -1.0
    private var doubleBlink = false
    private var nextBlink = now() + 2
    private var lastTime: Double? = null
    private var nextSaccade = 0.0
    private var wanderX = 0.0
    private var wanderY = -0.3
    private var jitterX = 0.0
    private var jitterY = 0.0
    private var nextJitter = 0.0
    private var nextHop = now() + 6

    /** Mood set on the phone itself (waking up, talking). */
    @Volatile var localMood: Mood? = null
    /** Loudness of the chirp playing on this phone, 0…1. */
    var localTalk: () -> Double = { 0.0 }
    /** True while he's awake and talking with you. In follow mode his eyes stay locked on your mouse. */
    @Volatile var awake = false

    fun receive(face: FaceState) {
        target = face
        lastPacket = now()
    }

    fun step(now: Double): Frame {
        val dt = min(now - (lastTime ?: now), 1.0 / 20)
        lastTime = now

        val live = now - lastPacket < 2.5
        val wantedMood = localMood ?: if (live) target.mood else Mood.listening
        if (wantedMood != mood) {
            mood = wantedMood
            blinkStart = now          // blink through every mood change
            if (mood != Mood.sleepy && mood != Mood.resting) {
                hop.velocity += 260   // and a little bounce of surprise
                pupil.velocity += 3
            }
        }

        // Where to look, plus tiny darting movements so the eyes never sit dead still.
        var wantX: Double
        var wantY: Double
        if (live) {
            wantX = target.gazeX
            wantY = target.gazeY
        } else {
            if (now > nextSaccade) {
                wanderX = rand(-0.9, 0.9)
                wanderY = rand(-0.9, 0.4)
                nextSaccade = now + rand(0.6, 2.2)
            }
            wantX = wanderX
            wantY = wanderY
        }
        if (mood == Mood.thinking) {
            wantX = 0.6 + 0.08 * sin(now * 1.3)
            wantY = -0.85
        }
        if (now > nextJitter) {
            // While he's listening his eyes stay on your mouse; the livelier darting is for replying.
            val amount = if (!awake) 0.015 else when (mood) {
                Mood.talking -> 0.07
                Mood.thinking -> 0.05
                else -> 0.02
            }
            jitterX = rand(-amount, amount)
            jitterY = rand(-amount, amount)
            nextJitter = now + rand(0.5, 1.4)
        }
        gazeX.step(wantX + jitterX, dt)
        gazeY.step(wantY + jitterY, dt)

        var wantTalk = if (live) target.talk else if (localMood == Mood.talking) 0.5 + 0.5 * sin(now * 19) * sin(now * 7.3) else 0.0
        wantTalk = max(wantTalk, localTalk())
        talk += (wantTalk - talk) * min(1.0, dt * 25)

        // Expression targets per mood.
        var wantPupil = 1.0
        var wantLift = 0.0
        var wantTilt = 0.0
        var wantBlush = 0.25
        var wantSquint = 0.0
        when (mood) {
            Mood.listening -> { wantPupil = 1.12; wantLift = 10.0 }
            Mood.talking -> { wantPupil = 1.05; wantLift = 6 + talk * 18; wantTilt = 0.05 * sin(now * 2.3) }
            Mood.pointing -> { wantPupil = 0.95; wantLift = -4.0; wantTilt = -0.14 }
            Mood.thinking -> { wantPupil = 0.9; wantLift = 4.0; wantTilt = 0.22 }
            Mood.happy -> { wantPupil = 1.2; wantLift = 16.0; wantBlush = 0.85; wantSquint = 1.0 }
            Mood.resting -> { wantPupil = 0.9; wantLift = -8.0; wantBlush = 0.15 }
            Mood.sleepy -> { wantPupil = 0.88; wantLift = -10.0; wantTilt = 0.12; wantBlush = 0.15 }
        }
        pupil.step(wantPupil, dt)
        browLift.step(wantLift, dt)
        browTilt.step(wantTilt, dt)
        blush.step(wantBlush, dt)
        squint.step(wantSquint, dt)
        lean.step(-gazeX.value * 0.045, dt)

        // An occasional happy little hop when nothing much is going on.
        if (now > nextHop) {
            if (talk < 0.05 && mood != Mood.sleepy && mood != Mood.resting) hop.velocity += rand(180.0, 320.0)
            nextHop = now + rand(7.0, 14.0)
        }
        hop.step(0.0, dt)

        // Blinks, sometimes doubled. When he's drowsy they're slow and heavy.
        val drowsy = mood == Mood.sleepy
        if (now > nextBlink) {
            blinkStart = now
            doubleBlink = !drowsy && Random.nextDouble() < 0.25
            nextBlink = now + if (drowsy) rand(1.6, 3.0) else rand(2.0, 5.0)
        }
        val p = (now - blinkStart) / (if (drowsy) 0.7 else 0.15)
        var closed = if (p in 0.0..1.0) sin(PI * p) else 0.0
        if (doubleBlink && p in 1.3..2.3) closed = sin(PI * (p - 1.3))

        val breathe = sin(now * if (mood == Mood.resting) 1.1 else 1.8)
        return Frame(gazeX.value, gazeY.value, mood, talk, closed, breathe, now, pupil.value, browLift.value,
            browTilt.value, lean.value, hop.value, blush.value, squint.value.coerceIn(0.0, 1.0))
    }

    companion object {
        fun now(): Double = System.nanoTime() / 1e9
    }
}
