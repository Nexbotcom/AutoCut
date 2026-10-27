package com.autocut.app

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

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private var current: VideoInfo? = null
    private var highlights: List<Highlight> = emptyList()

    private val picker = registerForActivityResult(
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
                    "Name: ${info.name}\n" +
                    "Size: $mb MB\n" +
                    "Duration: ${fmt(info.durationMs / 1000.0)}\n" +
                    "Resolution: ${info.width}x${info.height}\n\n" +
                    "Tap 'Find highlights'."
                )
            } catch (e: Exception) {
                show("Could not read this video: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        status = TextView(this).apply {
            text = "Pick a video to start."
            textSize = 16f
        }
        val pick = Button(this).apply {
            text = "Pick video"
            setOnClickListener { picker.launch("video/*") }
        }
        val find = Button(this).apply {
            text = "Find highlights"
            setOnClickListener { findHighlights() }
        }
        root.addView(pick)
        root.addView(find)
        root.addView(ScrollView(this).apply { addView(status) })
        setContentView(root)
    }

    private fun show(msg: String) = runOnUiThread { status.text = msg }

    private fun fmt(sec: Double): String {
        val s = sec.toInt()
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun findHighlights() {
        val info = current ?: return show("Pick a video first.")
        show("Analyzing video... this can take a while for long videos.")
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
                show("Top highlights (took ${secs}s):\n\n" + lines.joinToString("\n"))
            }
        }
    }
}
