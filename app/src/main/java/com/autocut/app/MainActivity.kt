package com.autocut.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val vm: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
            ) {
                Surface(Modifier.fillMaxSize()) { EditorScreen(vm) }
            }
        }
    }
}

private fun fmt(sec: Double): String {
    val m = (sec / 60).toInt()
    val s = sec - m * 60
    return String.format(Locale.US, "%d:%04.1f", m, s)
}

@Composable
fun EditorScreen(vm: EditorViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    // Pause playback when the app goes to the background
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // Load the picked video into the player
    LaunchedEffect(state.video?.uri) {
        val u = state.video?.uri
        if (u != null) {
            player.setMediaItem(MediaItem.fromUri(u))
            player.prepare()
            player.pause()
        } else {
            player.clearMediaItems()
        }
    }

    // Preview one highlight: seek to it, play, stop at its end
    var previewEnd by remember { mutableLongStateOf(-1L) }
    var previewTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(previewTick) {
        if (previewEnd < 0) return@LaunchedEffect
        val end = previewEnd
        while (player.currentPosition < end) {
            delay(80)
            if (!player.playWhenReady) return@LaunchedEffect // user paused
        }
        player.pause()
    }
    val preview: (ClipItem) -> Unit = { c ->
        player.seekTo((c.startSec * 1000).toLong())
        player.play()
        previewEnd = (c.endSec * 1000).toLong()
        previewTick++
    }

    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) vm.loadVideo(uri) }

    val musicPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) vm.setMusic(uri) }

    val busy = state.busy != Busy.None
    val enabledClips = state.clips.filter { it.enabled }
    val finalLen = if (enabledClips.isEmpty()) 0.0
        else enabledClips.sumOf { it.endSec - it.startSec } - (enabledClips.size - 1) * 0.5
    val canRender = state.video != null && state.musicName != null && enabledClips.isNotEmpty()

    Column(Modifier.fillMaxSize().systemBarsPadding()) {

        // ---- Player ----
        if (state.video != null) {
            PlayerBox(player)
        } else {
            Box(
                Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                contentAlignment = Alignment.Center
            ) { Text("Pick a video to begin") }
        }

        // ---- Scrollable controls ----
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { videoPicker.launch("video/*") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Pick video") }
                    OutlinedButton(
                        onClick = { musicPicker.launch("audio/*") },
                        enabled = !busy,
                        modifier = Modifier.weight(1f)
                    ) { Text("Pick music") }
                }
            }

            item {
                val v = state.video
                Text(
                    buildString {
                        if (v != null) {
                            append("${v.name}\n")
                            append("${fmt(v.durationMs / 1000.0)}  |  ${v.width}x${v.height}\n")
                        }
                        append("Music: ${state.musicName ?: "not selected"}")
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            item {
                Column {
                    Text("Number of highlights: ${state.clipCount}")
                    Slider(
                        value = state.clipCount.toFloat(),
                        onValueChange = { vm.setClipCount(it.roundToInt()) },
                        valueRange = 2f..10f,
                        steps = 7,
                        enabled = !busy
                    )
                    Text("Clip length: ${state.clipLen.roundToInt()} s")
                    Slider(
                        value = state.clipLen,
                        onValueChange = { vm.setClipLen(it.roundToInt().toFloat()) },
                        valueRange = 2f..8f,
                        steps = 5,
                        enabled = !busy
                    )
                }
            }

            item {
                Button(
                    onClick = { vm.analyze() },
                    enabled = state.video != null && !busy,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Find highlights") }
            }

            state.message?.let { msg ->
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(msg, style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { vm.clearMessage() }) { Text("OK") }
                        }
                    }
                }
            }

            items(state.clips, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = c.enabled,
                                onCheckedChange = { vm.toggleClip(c.id) },
                                enabled = !busy
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "${fmt(c.startSec)} - ${fmt(c.endSec)}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    "${"%.1f".format(Locale.US, c.endSec - c.startSec)} s  |  score ${"%.2f".format(Locale.US, c.score)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            FilledTonalButton(onClick = { preview(c) }) { Text("Preview") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { vm.nudge(c.id, -0.5, 0.0) },
                                enabled = !busy, modifier = Modifier.weight(1f)
                            ) { Text("Start -0.5") }
                            TextButton(
                                onClick = { vm.nudge(c.id, 0.5, 0.0) },
                                enabled = !busy, modifier = Modifier.weight(1f)
                            ) { Text("Start +0.5") }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(
                                onClick = { vm.nudge(c.id, 0.0, -0.5) },
                                enabled = !busy, modifier = Modifier.weight(1f)
                            ) { Text("End -0.5") }
                            TextButton(
                                onClick = { vm.nudge(c.id, 0.0, 0.5) },
                                enabled = !busy, modifier = Modifier.weight(1f)
                            ) { Text("End +0.5") }
                        }
                    }
                }
            }
        }

        // ---- Bottom action bar ----
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                when (state.busy) {
                    Busy.Rendering -> {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Rendering ${(state.progress * 100).toInt()}%")
                        OutlinedButton(
                            onClick = { vm.cancel() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancel") }
                    }
                    Busy.Analyzing -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Analyzing video...")
                        OutlinedButton(
                            onClick = { vm.cancel() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Cancel") }
                    }
                    Busy.Loading -> {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        Text("Loading video...")
                    }
                    Busy.None -> {
                        if (enabledClips.isNotEmpty()) {
                            Text(
                                "Final length about ${fmt(finalLen)}  |  ${enabledClips.size} clips",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(
                            onClick = { vm.render() },
                            enabled = canRender,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Render edit") }
                    }
                }
            }
        }
    }
}
