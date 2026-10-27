package com.autocut.app

import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private var current: VideoInfo? = null
    private var highlights: List<Highlight> = emptyList()
    private var musicUri: Uri? = null
    private var sfxUri: Uri? = null

    private val videoPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        show("Reading video info...")
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { VideoSource.read(this@MainActivity, uri) }
                current = info
                highlights = emptyList()
                val mb = if (info.sizeBytes > 0) info.sizeBytes / (1024 * 1024) else -1
                show(
                    "Name: ${info.name}\nSize: $mb MB\n" +
                    "Duration: ${fmt(info.durationMs / 1000.0)}\n" +
                    "Resolution: ${info.width}x${info.height}\n\nTap 'Find highlights'."
                )
            } catch (e: Exception) {
                show("Could not read this video: ${e.message}")
            }
        }
    }

    private val musicPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) { musicUri = uri; show("Music selected.\n" + selections()) }
    }

    private val sfxPicker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) { sfxUri = uri; show("Sound effect selected.\n" + selections()) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        status = TextView(this).apply {
            text = "1) Pick video  2) Find highlights\n3) Pick music  4) Pick sound effect\n5) Render edit"
            textSize = 16f
        }

        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }

        root.addView(button("1. Pick video") { videoPicker.launch("video/*") })
        root.addView(button("2. Find highlights") { findHighlights() })
        root.addView(button("3. Pick music") { musicPicker.launch("audio/*") })
        root.addView(button("4. Pick sound effect") { sfxPicker.launch("audio/*") })
        root.addView(button("5. Render edit") { render() })
        root.addView(ScrollView(this).apply { addView(status) })
        setContentView(root)
    }

    private fun show(msg: String) = runOnUiThread { status.text = msg }

    private fun fmt(sec: Double): String {
        val s = sec.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun selections() =
        "Music: ${if (musicUri != null) "set" else "not set"}\n" +
        "Sound effect: ${if (sfxUri != null) "set" else "not set"}"

    private fun findHighlights() {
        val info = current ?: return show("Pick a video first.")
        show("Analyzing video...")
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            val result = try {
                HighlightDetector.detect(this@MainActivity, info)
            } catch (e: Exception) {
                show("Analysis failed: ${e.message}")
                return@launch
            }
            highlights = result
            val secs = (System.currentTimeMillis() - started) / 1000
            if (result.isEmpty()) {
                show("No highlights found (took ${secs}s).")
            } else {
                val lines = result.mapIndexed { i, h ->
                    "${i + 1}. ${fmt(h.startSec)} - ${fmt(h.endSec)}  (score ${"%.2f".format(h.score)})"
                }
                show("Top highlights (took ${secs}s):\n\n" + lines.joinToString("\n") +
                    "\n\n" + selections())
            }
        }
    }

    private fun render() {
        val info = current ?: return show("Pick a video first.")
        if (highlights.isEmpty()) return show("Run 'Find highlights' first.")
        val music = musicUri ?: return show("Pick a music file first.")
        val sfx = sfxUri ?: return show("Pick a sound effect first.")

        show("Rendering...")
        lifecycleScope.launch {
            val started = System.currentTimeMillis()
            val out = File(cacheDir, "AutoCut_${System.currentTimeMillis()}.mp4")
            val (ok, log) = EditRenderer.render(
                this@MainActivity, info, highlights, music, sfx, out
            ) { p -> show("Rendering... ${(p * 100).toInt()}%") }

            val secs = (System.currentTimeMillis() - started) / 1000
            if (ok) {
                val where = withContext(Dispatchers.IO) {
                    EditRenderer.saveToGallery(this@MainActivity, out)
                }
                show("Done in ${secs}s.\n\nSaved to: $where\n\nOpen your gallery and play it.")
            } else {
                show("Render failed after ${secs}s.\n\n$log")
            }
        }
    }
}
