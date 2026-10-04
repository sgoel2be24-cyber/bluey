package dev.googly.bluey

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.SentimentSatisfied
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

private val green = hex(0x5BE49B)
private val red = hex(0xE5547A)

/**
 * Bluey's own app screen: start or end a session, see past sessions and their transcripts, and settings.
 * Opened from the faint button in the top-right corner of his face.
 */
@Composable
fun BlueyAppScreen(googly: Googly, onClose: () -> Unit) {
    var openID by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler { if (openID != null) openID = null else onClose() }

    Box(Modifier.fillMaxSize().background(Color.Black).blockTouches()) {
        AnimatedContent(
            targetState = openID,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally { it } + fadeIn()) togetherWith (slideOutHorizontally { -it / 3 } + fadeOut())
                } else {
                    (slideInHorizontally { -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { it } + fadeOut())
                }
            },
            label = "bluey app",
        ) { id ->
            if (id == null) {
                Row(Modifier.fillMaxSize()) {
                    Sidebar(googly, onClose)
                    Box(Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.End))) {
                        SessionList(googly.store) { openID = it }
                    }
                }
            } else {
                Box(Modifier.windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))) {
                    SessionDetail(googly.store, id) { openID = null }
                }
            }
        }
    }
}

// region Left: who he is, start/end, settings

@Composable
private fun Sidebar(googly: Googly, onClose: () -> Unit) {
    val link = googly.link
    val live = googly.live
    val asleep = live.state == LiveVoice.State.Asleep
    val disabled = !link.connected && asleep

    Column(
        Modifier
            .fillMaxHeight()
            .background(Palette.panel.copy(alpha = 0.6f))
            // The panel runs under the camera cutout; its contents stay clear of it.
            .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Start))
            .width(300.dp)
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            MiniBluey(Modifier.size(54.dp, 48.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Bluey", style = Fonts.fredoka(28), color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(if (link.connected) green else Palette.inkSoft.copy(alpha = 0.5f), CircleShape))
                    Text(
                        if (link.connected) link.macName ?: link.currentMac ?: "Mac" else "Looking for your Mac",
                        style = Fonts.plexSans(12), color = Palette.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .alpha(if (disabled) 0.5f else 1f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (asleep) Palette.berryFill else androidx.compose.ui.graphics.SolidColor(red))
                .tap(enabled = !disabled) {
                    live.toggle()
                    if (asleep) onClose()  // back to his face when starting
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (asleep) Icons.Filled.PlayArrow else Icons.Filled.Stop, null, tint = Color.White)
            Text(if (asleep) "Start session" else "End session", style = Fonts.fredoka(18), color = Color.White)
        }

        Text(
            if (asleep) "He listens the whole session. Hold his face to ask something, let go and he answers."
            else "Session running. Hold his face to ask; everything you say is saved here.",
            style = Fonts.plexSans(12), color = Palette.inkSoft,
        )

        HorizontalDivider(color = Color.White.copy(alpha = 0.08f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label("Chirp volume")
            VolumeSlider(live.volume) { live.volume = it }
        }

        if (link.macs.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label("Mac")
                MacPicker(link, rowHeight = 34)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(14.dp))
                .tap(onClick = onClose),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.SentimentSatisfied, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Text("Back to his face", style = Fonts.plexSans(15, FontWeight.SemiBold), color = Color.White)
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = Fonts.plexMono(11), color = Palette.inkSoft)
}

// endregion

// region Right: sessions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionList(store: SessionStore, onOpen: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sessions", style = Fonts.fredoka(24), color = Color.White)
            Spacer(Modifier.weight(1f))
            Text("${store.sessions.size}", style = Fonts.plexMono(13), color = Palette.inkSoft)
        }

        if (store.sessions.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(horizontal = 22.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.ChatBubbleOutline, null, tint = Palette.berry2, modifier = Modifier.size(34.dp))
                Text("No sessions yet", style = Fonts.fredoka(18), color = Color.White)
                Text("Start one and your transcript and his replies show up here.", style = Fonts.plexSans(13),
                    color = Palette.inkSoft, textAlign = TextAlign.Center)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(store.sessions, key = { it.id }) { session ->
                    // Swipe a session to the left to delete it.
                    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = {
                        if (it == SwipeToDismissBoxValue.EndToStart) store.delete(session.id)
                        it == SwipeToDismissBoxValue.EndToStart
                    })
                    SwipeToDismissBox(
                        swipe,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(Modifier.fillMaxSize().background(red, RoundedCornerShape(14.dp)).padding(end = 20.dp),
                                contentAlignment = Alignment.CenterEnd) {
                                Icon(Icons.Filled.Delete, "Delete", tint = Color.White)
                            }
                        },
                    ) {
                        SessionRow(session, live = session.id == store.currentID) { onOpen(session.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: BlueySession, live: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.panel, RoundedCornerShape(14.dp))
            .tap(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(session.title, style = Fonts.plexSans(15, FontWeight.SemiBold), color = Color.White)
                if (live) {
                    Text("LIVE", style = Fonts.plexMono(10, FontWeight.Bold), color = Color.Black,
                        modifier = Modifier.background(green, RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
            Text(session.summary, style = Fonts.plexSans(13), color = Palette.inkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(duration(session.duration), style = Fonts.plexMono(12), color = Palette.inkSoft)
            Text("${session.questionCount} asked", style = Fonts.plexMono(11), color = Palette.berry1)
        }
    }
}

private fun duration(seconds: Long): String =
    if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)

// endregion

// region One session

/** One session as a chat: what you said in grey, your questions in blue, his replies in white bubbles. */
@Composable
private fun SessionDetail(store: SessionStore, id: String, onBack: () -> Unit) {
    val session = store.sessions.firstOrNull { it.id == id }
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(
            Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIcon(Icons.Filled.ChevronLeft, "Back", Color.White, Color.White.copy(alpha = 0.08f), disc = 40, iconSize = 24,
                onClick = onBack)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(session?.title ?: "Session", style = Fonts.fredoka(20), color = Color.White)
                if (session != null) {
                    Text("${duration(session.duration)} · ${session.questionCount} asked · ${session.entries.size} lines",
                        style = Fonts.plexMono(11), color = Palette.inkSoft)
                }
            }
            if (session != null) {
                CircleIcon(Icons.Filled.Delete, "Delete session", Color.White, Color.White.copy(alpha = 0.08f), disc = 40) {
                    confirmDelete = true
                }
                CircleIcon(Icons.Filled.Share, "Share transcript", Color.White, Color.White.copy(alpha = 0.08f), disc = 40) {
                    share(context, session.exportText)
                }
            }
        }

        val entries = session?.entries?.filter { it.text.isNotEmpty() } ?: emptyList()
        val list = rememberLazyListState()
        var scrolled by remember { mutableStateOf(false) }
        LaunchedEffect(entries.size) {
            if (entries.isEmpty()) return@LaunchedEffect
            if (scrolled) list.animateScrollToItem(entries.lastIndex) else list.scrollToItem(entries.lastIndex)
            scrolled = true
        }
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.id }) { EntryView(it) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this session?") },
            text = { Text("Its transcript will be gone from this phone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    store.delete(id)
                    onBack()
                }) { Text("Delete", color = red) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
            containerColor = Palette.panel,
        )
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, "Share session"))
}

@Composable
private fun EntryView(entry: TranscriptEntry) {
    when (entry.kind) {
        TranscriptEntry.Kind.heard -> Text(entry.text, style = Fonts.plexSans(14), color = Palette.inkSoft,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp))

        TranscriptEntry.Kind.asked -> Column(
            Modifier.fillMaxWidth().padding(start = 120.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text("You asked", style = Fonts.plexMono(10), color = Palette.berry1)
            Text(entry.text, style = Fonts.plexSans(15, FontWeight.Medium), color = Color.White,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(Palette.berryFill)
                    .padding(horizontal = 14.dp, vertical = 10.dp))
        }

        TranscriptEntry.Kind.reply -> Row(
            Modifier.fillMaxWidth().padding(end = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            MiniBluey(Modifier.size(28.dp, 25.dp))
            Text(entry.text, style = Fonts.fredoka(16), color = Palette.ink,
                modifier = Modifier.background(Color.White, RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
        }

        TranscriptEntry.Kind.report -> ReportCard(entry.text)
    }
}

/** A research report: its headline, with the rest folded away until you tap it. */
@Composable
private fun ReportCard(text: String) {
    var open by remember { mutableStateOf(false) }
    val parts = text.split("\n\n")
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 80.dp)
            .background(hex(0xEEF0FF), shape)
            .border(2.dp, Palette.berry2, shape)
            .padding(14.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.tap { open = !open }, horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FindInPage, null, tint = Palette.berry2)
            Text(parts.firstOrNull() ?: "Research", style = Fonts.fredoka(16), color = Palette.ink, modifier = Modifier.weight(1f))
            Box(Modifier.size(24.dp).background(Palette.berry2, CircleShape), contentAlignment = Alignment.Center) {
                Icon(if (open) Icons.Filled.Remove else Icons.Filled.Add, if (open) "Fold" else "Expand",
                    tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        Text(parts.drop(1).joinToString("\n\n"), style = Fonts.plexSans(14), color = Palette.ink,
            maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis)
    }
}

// endregion
