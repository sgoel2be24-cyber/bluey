package dev.googly.bluey

import android.graphics.BlurMaskFilter
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** The blob outline: CSS `border-radius: 52% 48% 46% 54% / 58% 56% 44% 42%`. */
object Blob {
    fun path(r: Rect): Path {
        val w = r.width
        val h = r.height
        // Horizontal and vertical radii for top-left, top-right, bottom-right, bottom-left.
        val tlW = 0.52f * w; val tlH = 0.58f * h
        val trW = 0.48f * w; val trH = 0.56f * h
        val brW = 0.46f * w; val brH = 0.44f * h
        val blW = 0.54f * w; val blH = 0.42f * h
        val k = 1 - 0.5523f  // cubic approximation of a quarter ellipse
        return Path().apply {
            moveTo(r.left + tlW, r.top)
            lineTo(r.right - trW, r.top)
            cubicTo(r.right - trW * k, r.top, r.right, r.top + trH * k, r.right, r.top + trH)
            lineTo(r.right, r.bottom - brH)
            cubicTo(r.right, r.bottom - brH * k, r.right - brW * k, r.bottom, r.right - brW, r.bottom)
            lineTo(r.left + blW, r.bottom)
            cubicTo(r.left + blW * k, r.bottom, r.left, r.bottom - blH * k, r.left, r.bottom - blH)
            lineTo(r.left, r.top + tlH)
            cubicTo(r.left, r.top + tlH * k, r.left + tlW * k, r.top, r.left + tlW, r.top)
            close()
        }
    }

    /** The little blueberry crown on top of his head (SVG path from the design, 60×40 box). */
    fun crown(r: Rect): Path {
        val points = listOf(30 to 4, 36 to 16, 52 to 12, 40 to 24, 46 to 36, 30 to 28, 14 to 36, 20 to 24, 8 to 12, 24 to 16)
        return Path().apply {
            points.forEachIndexed { i, (x, y) ->
                val px = r.left + x / 60f * r.width
                val py = r.top + y / 40f * r.height
                if (i == 0) moveTo(px, py) else lineTo(px, py)
            }
            close()
        }
    }

    /** The soft white shine on his head. */
    fun shine(body: Rect): Brush = Brush.radialGradient(
        listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0f)),
        center = Offset(body.left + 246, body.top + 126),
        radius = 243f,
    )
}

/** Redraws every frame while it's on screen. Read `.value` inside a draw block to take part. */
@Composable
fun rememberFrameTick(running: Boolean = true): State<Long> {
    val tick = remember { mutableLongStateOf(0L) }
    LaunchedEffect(running) {
        if (running) while (true) withFrameNanos { tick.longValue = it }
    }
    return tick
}

/** The landscape face on pure black: he fills the screen and peeks up from the bottom edge. */
@Composable
fun FaceView(animator: FaceAnimator, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val tick = rememberFrameTick()
    val glow = remember { FaceArt.glowBitmap() }
    Canvas(modifier.fillMaxSize()) {
        tick.value
        val frame = animator.step(FaceAnimator.now())
        FaceArt.draw(this, frame, measurer, glow)
    }
}

private object FaceArt {
    // The design is drawn on an 844 × 390 landscape phone.
    const val DESIGN_W = 844f
    const val DESIGN_H = 390f
    const val BODY_W = 820f
    const val BODY_H = 700f

    // The soft halo around him is blurred once into a small bitmap and then moved along with his body.
    const val GLOW_PAD = 130f
    const val GLOW_RES = 0.5f

    fun glowBitmap(): ImageBitmap {
        val w = ((BODY_W + 2 * GLOW_PAD) * GLOW_RES).toInt()
        val h = ((BODY_H + 2 * GLOW_PAD) * GLOW_RES).toInt()
        val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val r = GLOW_PAD * GLOW_RES
        val path = Blob.path(Rect(r, r, r + BODY_W * GLOW_RES, r + BODY_H * GLOW_RES)).asAndroidPath()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(128, 0x6C, 0x86, 0xF5)  // berry2 at 50%
            maskFilter = BlurMaskFilter(45f * GLOW_RES, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawPath(path, paint)
        return bitmap.asImageBitmap()
    }

    fun fillSkin(scope: DrawScope, path: Path, body: Rect) {
        scope.drawPath(path, Palette.berryBrush(body))
        scope.drawPath(path, Blob.shine(body))
    }

    fun draw(scope: DrawScope, f: FaceAnimator.Frame, measurer: TextMeasurer, glow: ImageBitmap) = with(scope) {
        drawRect(Color.Black)

        val scale = max(size.width / DESIGN_W, size.height / DESIGN_H)
        // Whole-body motion: breathing, talking bounce, idle hops, and leaning toward what he looks at.
        val bounce = (-f.talk * 16 + f.breathe * 3 - f.hop).toFloat()
        val body = Rect(12f, 44f + bounce, 12f + BODY_W, 44f + bounce + BODY_H)
        val pivot = Offset(body.center.x, body.bottom)
        val stretch = 1 + (f.hop / 900).coerceIn(-0.03, 0.03).toFloat()

        withTransform({
            translate((size.width - DESIGN_W * scale) / 2, size.height - DESIGN_H * scale)
            scale(scale, scale, Offset.Zero)
            rotate(Math.toDegrees(f.lean).toFloat(), pivot)
            scale(2 - stretch, stretch, pivot)
        }) {
            drawBody(f, body, measurer, glow)
        }
    }

    private fun DrawScope.drawBody(f: FaceAnimator.Frame, body: Rect, measurer: TextMeasurer, glow: ImageBitmap) {
        val blob = Blob.path(body)

        withTransform({
            translate(body.left - GLOW_PAD, body.top - GLOW_PAD - 10)
            scale(1 / GLOW_RES, 1 / GLOW_RES, Offset.Zero)
        }) {
            drawImage(glow)
        }

        drawPath(blob, Palette.berryBrush(body))
        clipPath(blob) {
            drawPath(blob, Blob.shine(body))
            // A little glossy sparkle on his head.
            drawOval(Color.White.copy(alpha = 0.35f), Offset(body.left + 190, body.top + 40), Size(46f, 22f))
        }

        val navy = Palette.nose
        val ink = Palette.ink
        drawPath(Blob.crown(Rect(Offset(body.left + 368, body.top - 20 - f.browLift.toFloat() * 0.3f), Size(84f, 56f))), navy)

        val eyeY = body.top + 157
        val eyes = listOf(Offset(body.left + 280, eyeY), Offset(body.left + 540, eyeY))
        val squash = 1 - f.talk * 0.12

        // Blushy cheeks, rosier when he's happy.
        for ((i, c) in eyes.withIndex()) {
            val side = if (i == 0) -1f else 1f
            val center = Offset(c.x + side * 62, c.y + 96 + 21)
            val blush = hex(0xF3A6D8, (0.25 + 0.45 * f.blush).toFloat().coerceIn(0f, 1f))
            withTransform({ scale(1f, 42f / 90f, center) }) {
                drawCircle(
                    Brush.radialGradient(
                        0f to blush, 0.64f to blush, 0.82f to blush.copy(alpha = blush.alpha / 2), 1f to blush.copy(alpha = 0f),
                        center = center, radius = 55f,
                    ),
                    radius = 55f, center = center,
                )
            }
        }

        for ((i, c) in eyes.withIndex()) {
            val side = if (i == 0) -1f else 1f

            // Little arched eyebrows do a lot of the acting.
            val browY = c.y - 112 - f.browLift.coerceIn(-10.0, 18.0).toFloat() * 0.7f
            val tilt = f.browTilt * side * -1 + if (f.mood == Mood.thinking && i == 1) -0.25 else 0.0
            withTransform({
                translate(c.x + side * 6, browY)
                rotate(Math.toDegrees(tilt).toFloat(), Offset.Zero)
            }) {
                val arch = Path().apply {
                    moveTo(-38f, 6f)
                    quadraticBezierTo(0f, -12f, 38f, 6f)
                }
                drawPath(arch, navy, style = Stroke(14f, cap = StrokeCap.Round))
            }

            when {
                f.mood == Mood.resting -> {
                    // Peacefully closed: a soft downward curve.
                    val shut = Path().apply {
                        moveTo(c.x - 62, c.y + 6)
                        quadraticBezierTo(c.x, c.y + 52, c.x + 62, c.y + 6)
                    }
                    drawPath(shut, ink, style = Stroke(18f, cap = StrokeCap.Round))
                }

                f.mood == Mood.happy && f.squint > 0.6 -> {
                    val arc = Path().apply {
                        moveTo(c.x - 60, c.y + 34)
                        quadraticBezierTo(c.x, c.y - 54, c.x + 60, c.y + 34)
                    }
                    drawPath(arc, ink, style = Stroke(24f, cap = StrokeCap.Round))
                }

                else -> {
                    val open = (max(0.06, 1 - f.closed) * squash).toFloat()
                    val white = Rect(Offset(c.x - 95, c.y - 95 * open), Size(190f, 190 * open))
                    val whitePath = Path().apply { addOval(white) }
                    drawPath(whitePath, Color.White)
                    if (open <= 0.2f) continue

                    var gx = f.gazeX
                    var gy = f.gazeY
                    val len = hypot(gx, gy)
                    if (len > 1) { gx /= len; gy /= len }
                    // Eyes converge a little when looking down close, and reach further for a livelier look.
                    val reach = 50f
                    val r = (46 * f.pupil).toFloat()
                    val px = c.x + gx.toFloat() * reach - side * 3
                    var py = c.y + gy.toFloat() * reach * open
                    if (f.mood == Mood.sleepy) py = max(py, c.y) + 28  // eyes sink under heavy lids
                    clipPath(whitePath) {
                        drawCircle(ink, r, Offset(px, py))
                        // Two catchlights make them shine.
                        drawOval(Color.White, Offset(px - r * 0.58f, py - r * 0.72f), Size(r * 0.56f, r * 0.56f))
                        drawOval(Color.White.copy(alpha = 0.85f), Offset(px + r * 0.28f, py + r * 0.22f), Size(r * 0.24f, r * 0.24f))
                        // Happy cheeks push up from below as he smiles.
                        if (f.squint > 0.02) {
                            val lid = Rect(Offset(c.x - 110, c.y + 95 * open - 70 * f.squint.toFloat()), Size(220f, 160f))
                            fillSkin(this, Path().apply { addOval(lid) }, body)
                        }
                        // Heavy, droopy lids when he's getting sleepy.
                        if (f.mood == Mood.sleepy) {
                            val droop = (0.5 + 0.06 * sin(f.time * 1.3)).toFloat()
                            fillSkin(this, Path().apply {
                                addRect(Rect(Offset(c.x - 100, white.top - 10), Size(200f, white.height * droop + 10)))
                            }, body)
                            val y = white.top + white.height * droop
                            drawLine(navy.copy(alpha = 0.7f), Offset(c.x - 92, y), Offset(c.x + 92, y), 8f, StrokeCap.Round)
                        }
                        // A soft upper lid when he's focused on pointing.
                        if (f.mood == Mood.pointing) {
                            fillSkin(this, Path().apply { addRect(Rect(Offset(c.x - 100, white.top - 20), Size(200f, 40f))) }, body)
                        }
                    }
                }
            }
        }

        if (f.mood == Mood.resting) {
            // Little z's floating up while he naps.
            for (i in 0 until 3) {
                val phase = (f.time * 0.35 + i / 3.0) % 1.0
                val zSize = 26f + i * 8
                val layout = measurer.measure(AnnotatedString("z"), Fonts.fredoka(1).copy(fontSize = zSize.toSp()))
                val x = body.left + 660 + phase.toFloat() * 70 + sin(phase * 6).toFloat() * 8
                val y = body.top + 90 - phase.toFloat() * 110
                drawText(layout, Color.White, Offset(x - layout.size.width / 2f, y - layout.size.height / 2f),
                    alpha = (0.9 * sin(PI * phase)).toFloat().coerceIn(0f, 1f))
            }
        }

        if (f.mood == Mood.thinking) {
            for (i in 0 until 3) {
                val wobble = (sin(f.time * 3 + i) * 4).toFloat()
                drawOval(Color.White.copy(alpha = 1 - i * 0.3f),
                    Offset(body.left + 690 + i * 26, body.top + 40 - i * 12 + wobble), Size(16f, 16f))
            }
        }

        // His little mouth-nose: opens as he talks, curls into a smile when he's happy.
        val talk = f.talk.toFloat()
        val noseW = 60 - talk * 8
        val noseH = 38 + talk * 34
        val nose = Rect(Offset(body.left + 410 - noseW / 2, body.top + 272 - talk * 6), Size(noseW, noseH))
        if (f.mood == Mood.happy && f.talk < 0.1) {
            val smile = Path().apply {
                moveTo(nose.left - 8, nose.top + 8)
                quadraticBezierTo(nose.center.x, nose.top + 58, nose.right + 8, nose.top + 8)
                close()
            }
            drawPath(smile, navy)
        } else {
            drawOval(navy, nose.topLeft, nose.size)
            if (f.talk > 0.25) {
                drawOval(hex(0xE58BC4, 0.9f), Offset(nose.center.x - noseW * 0.28f, nose.bottom - noseH * 0.38f),
                    Size(noseW * 0.56f, noseH * 0.3f))
            }
        }
    }
}
