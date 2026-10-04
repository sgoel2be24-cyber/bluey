package dev.googly.bluey

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.RoundedCorner
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Stops taps falling through an overlay onto his face underneath. */
fun Modifier.blockTouches(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope { while (true) awaitPointerEvent() }
}

/** A plain tap target with no ripple, like the iOS buttons. */
@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)

@Composable
fun GooglyRoot(googly: Googly) {
    val link = googly.link
    val live = googly.live
    var showPairing by remember { mutableStateOf(true) }
    var showApp by remember { mutableStateOf(false) }
    val view = LocalView.current

    LaunchedEffect(link.connected) { if (link.connected) showPairing = false }

    MaterialTheme(colorScheme = darkColorScheme(primary = Palette.berry2, secondary = Palette.berry1, background = Color.Black)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            FaceView(
                googly.animator,
                Modifier.fillMaxSize().pointerInput(live) {
                    detectTapGestures(
                        onDoubleTap = { live.toggle() },  // double tap: wake up / back to follow mode
                        onPress = {
                            // Press and hold the screen to ask him something; let go and he answers.
                            coroutineScope {
                                var holding = false
                                val hold = launch {
                                    delay(300)  // a quick tap isn't a hold
                                    holding = true
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    live.beginAsk()
                                }
                                tryAwaitRelease()
                                hold.cancel()
                                if (holding) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    live.endAsk()
                                }
                            }
                        },
                    )
                },
            )

            ModeIndicator(live.state)

            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.displayCutout)) {
                // Bluey's app: sessions, transcripts and settings.
                CircleIcon(
                    Icons.Filled.GridView, "Open Bluey", tint = Color.White.copy(alpha = 0.35f),
                    fill = Color.White.copy(alpha = 0.06f),
                    modifier = Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 62.dp),
                ) { showApp = true }
                SoundButton(link, live, Modifier.align(Alignment.TopEnd).padding(top = 10.dp, end = 14.dp))
            }

            AnimatedVisibility(showPairing && !link.connected, enter = fadeIn(), exit = fadeOut(tween(300))) {
                PairingScreen { showPairing = false }
            }

            AnimatedVisibility(
                showApp,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
            ) {
                BlueyAppScreen(googly) { showApp = false }
            }
        }
    }
}

/** A faint round icon button: a 44 dp tap target around a 34 dp disc. */
@Composable
fun CircleIcon(
    icon: ImageVector,
    description: String,
    tint: Color,
    fill: Color,
    modifier: Modifier = Modifier,
    disc: Int = 34,
    iconSize: Int = 18,
    onClick: () -> Unit,
) {
    Box(modifier.size(44.dp).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.size(disc.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, description, tint = tint, modifier = Modifier.size(iconSize.dp))
        }
    }
}

// region Mode indicator

private class Look(val color: Color, val label: String?, val icon: ImageVector, val border: Float, val pulse: Double)

private val listeningBlue = hex(0x4F8BFF)

private fun look(state: LiveVoice.State) = when (state) {
    LiveVoice.State.Asleep -> Look(Palette.inkSoft, null, Icons.Filled.Visibility, 0f, 0.0)
    LiveVoice.State.Waking -> Look(hex(0xFFD66B), "Waking up", Icons.Filled.WbSunny, 6f, 1.6)
    LiveVoice.State.Listening -> Look(listeningBlue, "Listening · hold to ask", Icons.Filled.Hearing, 10f, 0.0)
    LiveVoice.State.Asking -> Look(listeningBlue, "I'm all ears", Icons.Filled.Mic, 16f, 1.2)
    LiveVoice.State.Thinking -> Look(hex(0xC79BFF), "Thinking", Icons.Filled.AutoAwesome, 8f, 1.1)
    LiveVoice.State.Speaking -> Look(hex(0xFF9AD0), "Replying", Icons.Filled.ChatBubble, 8f, 0.0)
}

/** The phone screen's own corner radius in pixels (Android 12+), so the border lines up with the glass. */
private fun screenCornerRadius(view: View): Float? {
    if (Build.VERSION.SDK_INT < 31) return null
    val corner = view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT) ?: return null
    return corner.radius.toFloat().takeIf { it > 0 }
}

/**
 * Shows which mode he's in: a solid border hugging the screen's own rounded corners (blue while he listens),
 * plus a small label in the corner. Following your mouse is just a little eye icon. No glows.
 */
@Composable
fun ModeIndicator(state: LiveVoice.State) {
    val look = look(state)
    val color by animateColorAsState(look.color, tween(250), label = "mode color")
    val border by animateFloatAsState(look.border, tween(250), label = "mode border")
    val tick = rememberFrameTick(look.pulse > 0)
    val view = LocalView.current

    Canvas(Modifier.fillMaxSize()) {
        tick.value
        if (border < 0.5f) return@Canvas
        val wave = if (look.pulse > 0) 0.5 + 0.5 * sin(FaceAnimator.now() * look.pulse * 2 * PI) else 1.0
        val alpha = if (look.pulse > 0) (0.65 + 0.35 * wave).toFloat() else 1f
        val b = border.dp.toPx()
        val radius = screenCornerRadius(view) ?: 28.dp.toPx()
        drawRoundRect(
            color.copy(alpha = alpha),
            topLeft = Offset(b / 2, b / 2),
            size = Size(size.width - b, size.height - b),
            cornerRadius = CornerRadius(max(0f, radius - b / 2)),
            style = Stroke(b),
        )
    }

    val quiet = look.label == null
    Row(
        Modifier
            .windowInsetsPadding(WindowInsets.displayCutout)
            .padding(top = (14 + border).dp, start = (24 + border).dp)
            .defaultMinSize(34.dp, 34.dp)
            .background(if (quiet) Color.White.copy(alpha = 0.06f) else color, RoundedCornerShape(50))
            .padding(horizontal = if (quiet) 0.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (quiet) color.copy(alpha = 0.7f) else Color.White
        Icon(look.icon, null, tint = ink, modifier = Modifier.size(16.dp))
        look.label?.let { Text(it, style = Fonts.fredoka(16), color = ink) }
    }
}

// endregion

// region Sound button

/** A tiny, quiet speaker button in the top-right corner: chirp volume, a test hi, and which Mac to pair with. */
@Composable
fun SoundButton(link: MacLink, live: LiveVoice, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    val touched = { lastTouch = System.nanoTime() }

    LaunchedEffect(open, lastTouch) {
        // Tuck the panel away after a few quiet seconds so it never ends up in a shot.
        if (open) {
            delay(6000)
            open = false
        }
    }

    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val volume = live.volume
        val speaker = when {
            volume < 0.01 -> Icons.AutoMirrored.Filled.VolumeOff
            volume < 0.5 -> Icons.AutoMirrored.Filled.VolumeDown
            else -> Icons.AutoMirrored.Filled.VolumeUp
        }
        CircleIcon(
            if (open) Icons.Filled.Close else speaker,
            if (open) "Close sound settings" else "Sound settings",
            tint = Color.White.copy(alpha = if (open) 0.9f else 0.35f),
            fill = Color.White.copy(alpha = if (open) 0.14f else 0.06f),
        ) {
            open = !open
            touched()
        }

        AnimatedVisibility(
            open,
            enter = fadeIn() + scaleIn(initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut() + scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                Modifier
                    .width(260.dp)
                    .background(Palette.panel.copy(alpha = 0.95f), RoundedCornerShape(18.dp))
                    .blockTouches()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("CHIRP VOLUME", style = Fonts.plexMono(12), color = Palette.inkSoft)
                    Spacer(Modifier.weight(1f))
                    Text("${(volume * 100).toInt()}%", style = Fonts.plexMono(13), color = Color.White)
                }
                VolumeSlider(volume) { live.volume = it; touched() }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .alpha(if (link.connected) 1f else 0.4f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.berryFill)
                        .tap(enabled = link.connected) { live.sayHi(); touched() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (live.state == LiveVoice.State.Asleep) "Wake him up" else "Say hi",
                        style = Fonts.plexSans(15, FontWeight.SemiBold), color = Color.White)
                }
                if (link.macs.size > 1) {
                    Text("MAC", style = Fonts.plexMono(12), color = Palette.inkSoft)
                    MacPicker(link, rowHeight = 36) { touched() }
                }
                if (!link.connected) {
                    Text("Connect to your Mac to talk to him.", style = Fonts.plexSans(12), color = Palette.inkSoft)
                }
            }
        }
    }
}

/** A slider like the iPhone's: a round white thumb on a thin berry track. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VolumeSlider(value: Double, onChange: (Double) -> Unit) {
    val colors = SliderDefaults.colors(
        thumbColor = Color.White,
        activeTrackColor = Palette.berry2,
        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
    )
    val source = remember { MutableInteractionSource() }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toDouble()) },
        colors = colors,
        interactionSource = source,
        thumb = { Box(Modifier.size(24.dp).shadow(3.dp, CircleShape).background(Color.White, CircleShape)) },
        track = { state ->
            SliderDefaults.Track(
                state, Modifier.height(4.dp), colors = colors,
                drawStopIndicator = null, thumbTrackGapSize = 0.dp,
            )
        },
    )
}

/** Every Mac on the Wi-Fi; tap one to pair with it. */
@Composable
fun MacPicker(link: MacLink, rowHeight: Int, onPick: () -> Unit = {}) {
    for (name in link.macs) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = rowHeight.dp).tap { link.choose(name); onPick() },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = Fonts.plexSans(14), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (name == link.currentMac) {
                Icon(if (link.connected) Icons.Filled.Check else Icons.Filled.MoreHoriz, null, tint = Palette.berry2,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}

// endregion

// region Pairing

/** Shown until the phone finds the Mac: a sleepy blob and how to wake him up. */
@Composable
fun PairingScreen(onPlay: () -> Unit) {
    Row(
        Modifier.fillMaxSize().background(Color.Black).blockTouches().padding(horizontal = 56.dp),
        horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SleepyBlob(Modifier.size(220.dp, 190.dp))
        Column(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Wake me up from your Mac", style = Fonts.fredoka(32), color = hex(0xF4F1FA))
            Text("Open Googly Eyes in your Mac's menu bar. Keep both on the same Wi-Fi and I'll find it.",
                style = Fonts.plexSans(16), color = Palette.inkSoft, modifier = Modifier.widthIn(max = 380.dp))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Palette.berry1, strokeWidth = 2.dp)
                Text("Looking for your Mac", style = Fonts.plexMono(13), color = Palette.inkSoft)
            }
            Text("Can't find it? On the Mac, turn on Googly Eyes in System Settings › Privacy & Security › Local Network.",
                style = Fonts.plexSans(12), color = Palette.inkSoft.copy(alpha = 0.7f), modifier = Modifier.widthIn(max = 380.dp))
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .background(Palette.panel, RoundedCornerShape(14.dp))
                    .tap(onClick = onPlay)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Play without the Mac", style = Fonts.plexSans(15, FontWeight.SemiBold), color = hex(0xF4F1FA))
            }
        }
    }
}

@Composable
private fun SleepyBlob(modifier: Modifier) {
    val tick = rememberFrameTick()
    Box(
        modifier.graphicsLayer {
            tick.value
            val s = 1 + 0.02f * sin(FaceAnimator.now() * 1.1).toFloat()
            scaleX = s
            scaleY = s
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val body = Rect(Offset.Zero, size)
            val blob = Blob.path(body)
            drawPath(blob, Palette.berryBrush(body), alpha = 0.9f)
            drawPath(blob, Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.5f), Color.White.copy(alpha = 0f)),
                center = Offset(0.3f * size.width, 0.22f * size.height), radius = 80.dp.toPx(),
            ), alpha = 0.9f)
            val eyeY = size.height / 2 - 18.dp.toPx()
            for (side in listOf(-1f, 1f)) {
                val cx = size.width / 2 + side * 36.dp.toPx()
                drawRoundRect(Palette.ink, Offset(cx - 23.dp.toPx(), eyeY - 3.5f.dp.toPx()),
                    Size(46.dp.toPx(), 7.dp.toPx()), CornerRadius(3.5f.dp.toPx()))
            }
            drawOval(Palette.nose, Offset(size.width / 2 + 4.dp.toPx() - 9.dp.toPx(), size.height / 2 + 21.dp.toPx() - 6.dp.toPx()),
                Size(18.dp.toPx(), 12.dp.toPx()))
        }
        Text("z", style = Fonts.fredoka(26), color = Palette.berry3, modifier = Modifier.graphicsLayer {
            tick.value
            translationX = 110.dp.toPx()
            translationY = (-95 - 4 * sin(FaceAnimator.now() * 1.5)).toFloat().dp.toPx()
        })
        Text("z", style = Fonts.fredoka(18), color = Palette.berry4, modifier = Modifier.graphicsLayer {
            tick.value
            translationX = 128.dp.toPx()
            translationY = (-122 - 4 * sin(FaceAnimator.now() * 1.5 + 1)).toFloat().dp.toPx()
        })
    }
}

/** A tiny Bluey: the blob with two googly eyes. */
@Composable
fun MiniBluey(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val body = Rect(Offset.Zero, size)
        drawPath(Blob.path(body), Palette.berryBrush(body))
        val y = size.height / 2 - w * 0.04f
        for (side in listOf(-1f, 1f)) {
            val c = Offset(size.width / 2 + side * w * 0.18f, y)
            drawCircle(Color.White, w * 0.13f, c)
            drawCircle(Palette.ink, w * 0.06f, c + Offset(w * 0.02f, w * 0.03f))
        }
    }
}

// endregion
