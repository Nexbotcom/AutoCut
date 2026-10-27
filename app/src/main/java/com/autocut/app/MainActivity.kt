package com.autocut.app

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private var current: VideoInfo? = null

    private val picker = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        show("Reading video info...")
        lifecycleScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { VideoSource.read(this@MainActivity, uri) }
                current = info
                val mb = if (info.sizeBytes > 0) info.sizeBytes / (1024 * 1024) else -1
                show(
                    "Name: ${info.name}\n" +
                    "Size: $mb MB\n" +
                    "Duration: ${info.durationMs / 1000}s\n" +
                    "Resolution: ${info.width}x${info.height}\n\n" +
                    "Tap 'Test FFmpeg read' to confirm FFmpeg can stream it."
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
        val test = Button(this).apply {
            text = "Test FFmpeg read"
            setOnClickListener { testRead() }
        }
        root.addView(pick)
        root.addView(test)
        root.addView(ScrollView(this).apply { addView(status) })
        setContentView(root)
    }

    private fun show(msg: String) = runOnUiThread { status.text = msg }

    private fun testRead() {
        val info = current ?: return show("Pick a video first.")
        show("Testing...")
        // Decodes 2 seconds and discards the output, proves FFmpeg can read the file
        FFmpegKit.executeAsync("-hide_banner -i ${info.ffmpegPath} -t 2 -f null -") { session ->
            if (ReturnCode.isSuccess(session.returnCode)) {
                show("FFmpeg read the video successfully.\n\n${info.name}\n${info.width}x${info.height}")
            } else {
                show("FFmpeg failed to read it.\n\n${session.output?.takeLast(600)}")
            }
        }
    }
}
