package com.autocut.app

import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.arthenica.ffmpegkit.FFmpegKit

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }
        status = TextView(this).apply {
            text = "Tap the button to test FFmpeg."
            textSize = 16f
        }
        val button = Button(this).apply {
            text = "Test FFmpeg"
            setOnClickListener { runCheck() }
        }
        root.addView(button)
        root.addView(status)
        setContentView(root)
    }

    private fun show(msg: String) = runOnUiThread { status.text = msg }

    private fun runCheck() {
        show("Checking encoders...")
        FFmpegKit.executeAsync("-hide_banner -encoders") { encSession ->
            val enc = encSession.output ?: ""
            val hwH264 = enc.contains("h264_mediacodec")
            val swH264 = enc.contains("libx264")

            FFmpegKit.executeAsync("-hide_banner -filters") { filSession ->
                val fil = filSession.output ?: ""
                val sidechain = fil.contains("sidechaincompress")
                val amix = fil.contains("amix")
                val adelay = fil.contains("adelay")

                show(
                    "FFmpeg is working.\n\n" +
                    "h264_mediacodec (hardware): $hwH264\n" +
                    "libx264 (software): $swH264\n" +
                    "sidechaincompress: $sidechain\n" +
                    "amix: $amix\n" +
                    "adelay: $adelay"
                )
            }
        }
    }
}
