package com.autocut.app

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ClipItem(
    val id: Int,
    val startSec: Double,
    val endSec: Double,
    val score: Double,
    val enabled: Boolean = true
)

enum class Busy { None, Loading, Analyzing, Rendering }

data class UiState(
    val video: VideoInfo? = null,
    val musicName: String? = null,
    val clips: List<ClipItem> = emptyList(),
    val clipCount: Int = 5,
    val clipLen: Float = 4f,
    val busy: Busy = Busy.None,
    val progress: Float = 0f,
    val message: String? = null,
    val outputPath: String? = null
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var musicUri: Uri? = null
    private var job: Job? = null

    private fun edit(block: UiState.() -> UiState) = _state.update { it.block() }
    private fun say(msg: String) = edit { copy(message = msg) }

    fun clearMessage() = edit { copy(message = null) }

    // ---------- Inputs ----------

    fun loadVideo(uri: Uri) {
        if (_state.value.busy != Busy.None) return
        job = viewModelScope.launch {
            edit { copy(busy = Busy.Loading, message = null) }
            try {
                val info = withContext(Dispatchers.IO) { VideoSource.read(app, uri) }
                edit {
                    copy(video = info, clips = emptyList(), outputPath = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Could not read this video: ${e.message}")
            } finally {
                edit { copy(busy = Busy.None) }
            }
        }
    }

    fun setMusic(uri: Uri) {
        musicUri = uri
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { displayName(uri) }
            edit { copy(musicName = name) }
        }
    }

    private fun displayName(uri: Uri): String {
        var name = "music"
        app.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0) name = c.getString(i) ?: name
            }
        }
        return name
    }

    // ---------- Settings ----------

    fun setClipCount(n: Int) = edit { copy(clipCount = n.coerceIn(2, 10)) }
    fun setClipLen(sec: Float) = edit { copy(clipLen = sec.coerceIn(2f, 8f)) }

    // ---------- Highlight review ----------

    fun toggleClip(id: Int) = edit {
        copy(clips = clips.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    // Move a clip's start and/or end by a number of seconds
    fun nudge(id: Int, dStart: Double, dEnd: Double) {
        val total = (_state.value.video?.durationMs ?: 0L) / 1000.0
        edit {
            copy(clips = clips.map { c ->
                if (c.id != id) c else {
                    val s = (c.startSec + dStart).coerceIn(0.0, (c.endSec - 1.5).coerceAtLeast(0.0))
                    val e = (c.endSec + dEnd).coerceIn(s + 1.5, maxOf(total, s + 1.5))
                    c.copy(startSec = s, endSec = e)
                }
            })
        }
    }

    // ---------- Analysis ----------

    fun analyze() {
        val s = _state.value
        val v = s.video ?: return say("Pick a video first.")
        if (s.busy != Busy.None) return

        job = viewModelScope.launch {
            edit { copy(busy = Busy.Analyzing, progress = 0f, message = null) }
            try {
                val found = withContext(Dispatchers.Default) {
                    HighlightDetector.detect(app, v, s.clipCount, s.clipLen.toDouble())
                }
                edit {
                    copy(
                        clips = found.mapIndexed { i, h ->
                            ClipItem(i, h.startSec, h.endSec, h.score)
                        },
                        message = if (found.isEmpty()) "No highlights found." else null
                    )
                }
            } catch (e: CancellationException) {
                say("Analysis cancelled.")
                throw e
            } catch (e: Exception) {
                say("Analysis failed: ${e.message}")
            } finally {
                edit { copy(busy = Busy.None) }
            }
        }
    }

    // ---------- Export ----------

    fun render() {
        val s = _state.value
        val v = s.video ?: return say("Pick a video first.")
        val m = musicUri ?: return say("Pick a music file first.")
        val chosen = s.clips.filter { it.enabled }
        if (chosen.isEmpty()) return say("Select at least one highlight.")
        if (s.busy != Busy.None) return

        job = viewModelScope.launch {
            edit { copy(busy = Busy.Rendering, progress = 0f, message = null, outputPath = null) }
            val file = File(app.cacheDir, "AutoCut_${System.currentTimeMillis()}.mp4")
            try {
                val clips = chosen.map { Highlight(it.startSec, it.endSec, it.score) }
                val (ok, log) = withContext(Dispatchers.Default) {
                    EditRenderer.render(app, v, clips, m, file) { p ->
                        edit { copy(progress = p.toFloat()) }
                    }
                }
                if (ok) {
                    val where = withContext(Dispatchers.IO) { EditRenderer.saveToGallery(app, file) }
                    edit { copy(outputPath = where, message = "Saved to $where") }
                } else {
                    say("Render failed.\n$log")
                }
            } catch (e: CancellationException) {
                file.delete()
                say("Render cancelled.")
                throw e
            } catch (e: Exception) {
                file.delete()
                say("Render failed: ${e.message}")
            } finally {
                edit { copy(busy = Busy.None) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }
}
