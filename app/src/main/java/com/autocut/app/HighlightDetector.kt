package com.autocut.app

import android.content.Context
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

data class Highlight(val startSec: Double, val endSec: Double, val score: Double)

object HighlightDetector {

    private const val FPS = 2          // video samples per second
    private const val CLIP_LEN = 4.0   // seconds per highlight
    private const val MIN_GAP = 6.0    // min seconds between highlights

    suspend fun detect(context: Context, video: VideoInfo, maxClips: Int = 5): List<Highlight> {
        val vFile = File(context.cacheDir, "video_scores.txt").also { it.delete() }
        val aFile = File(context.cacheDir, "audio_scores.txt").also { it.delete() }

        // Pass 1: frame-to-frame difference (motion and scene changes)
        run(arrayOf(
            "-hide_banner", "-i", video.ffmpegPath, "-an",
            "-vf", "fps=$FPS,scale=320:-2,select='gte(scene,0)',metadata=print:file=${vFile.absolutePath}",
            "-f", "null", "-"
        ))

        // Pass 2: audio loudness in 0.5s windows (skipped quietly if no audio track)
        run(arrayOf(
            "-hide_banner", "-i", video.ffmpegPath, "-vn",
            "-af", "aresample=8000,asetnsamples=n=4000:p=0,astats=metadata=1:reset=1," +
                "ametadata=print:key=lavfi.astats.Overall.RMS_level:file=${aFile.absolutePath}",
            "-f", "null", "-"
        ))

        val motion = parse(vFile, "lavfi.scene_score")
        val audio = parse(aFile, "lavfi.astats.Overall.RMS_level")
        if (motion.isEmpty()) return emptyList()

        val maxMotion = motion.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        val hasAudio = audio.isNotEmpty()
        val totalSec = video.durationMs / 1000.0

        // Score every half-second window
        val scores = motion.map { (idx, m) ->
            val v = m / maxMotion
            val a = audio[idx]?.let { ((it + 60.0) / 50.0).coerceIn(0.0, 1.0) } ?: 0.0
            val s = if (hasAudio) 0.6 * v + 0.4 * a else v
            idx to s
        }.sortedByDescending { it.second }

        // Pick top windows, keeping them spread apart
        val picked = mutableListOf<Pair<Double, Double>>() // time, score
        for ((idx, s) in scores) {
            val t = idx.toDouble() / FPS
            if (picked.size >= maxClips) break
            if (picked.none { kotlin.math.abs(it.first - t) < MIN_GAP }) picked.add(t to s)
        }

        return picked.map { (t, s) ->
            val start = (t - CLIP_LEN / 2).coerceAtLeast(0.0)
            val end = (start + CLIP_LEN).coerceAtMost(totalSec)
            Highlight(start, end, s)
        }.sortedBy { it.startSec }
    }

    private suspend fun run(args: Array<String>): Boolean =
        suspendCancellableCoroutine { cont ->
            FFmpegKit.executeWithArgumentsAsync(args) { session ->
                if (cont.isActive) cont.resume(ReturnCode.isSuccess(session.returnCode))
            }
        }

    // Reads FFmpeg's metadata print file into window-index -> value
    private fun parse(file: File, key: String): Map<Int, Double> {
        val out = HashMap<Int, Double>()
        if (!file.exists()) return out
        val timeRegex = Regex("pts_time:([0-9.eE+-]+)")
        var t = -1.0
        file.forEachLine { line ->
            if (line.startsWith("frame:")) {
                t = timeRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: -1.0
            } else if (line.startsWith(key) && t >= 0) {
                val raw = line.substringAfter("=").trim()
                val v = when {
                    raw.contains("-inf") -> -90.0
                    raw.contains("nan") || raw.contains("inf") -> null
                    else -> raw.toDoubleOrNull()
                }
                if (v != null) out[Math.round(t * FPS).toInt()] = v
            }
        }
        return out
    }
}
