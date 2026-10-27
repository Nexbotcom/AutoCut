package com.autocut.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

object EditRenderer {

    private fun f(x: Double) = String.format(Locale.US, "%.3f", x)

    suspend fun render(
        context: Context,
        video: VideoInfo,
        clips: List<Highlight>,
        music: Uri,
        sfx: Uri,
        out: File,
        onProgress: (Double) -> Unit
    ): Pair<Boolean, String> {
        val musicPath = FFmpegKitConfig.getSafParameterForRead(context, music)
        val sfxPath = FFmpegKitConfig.getSafParameterForRead(context, sfx)

        val n = clips.size
        val total = clips.sumOf { it.endSec - it.startSec }
        val t = f(total)

        // Inputs: one per clip (fast seek), then music, then sound effect
        val args = mutableListOf("-y", "-hide_banner")
        for (c in clips) {
            args += listOf("-ss", f(c.startSec), "-t", f(c.endSec - c.startSec), "-i", video.ffmpegPath)
        }
        args += listOf("-i", musicPath, "-i", sfxPath)

        val musicIdx = n
        val sfxIdx = n + 1
        val fc = StringBuilder()

        // Video: join the clips
        for (i in 0 until n) fc.append("[$i:v]setpts=PTS-STARTPTS[v$i];")
        for (i in 0 until n) fc.append("[v$i]")
        fc.append("concat=n=$n:v=1:a=0[vout];")

        // Sound effect times = the cut points in the final video
        val cuts = mutableListOf<Double>()
        var acc = 0.0
        clips.forEachIndexed { i, c ->
            if (i > 0) cuts += acc
            acc += c.endSec - c.startSec
        }
        if (cuts.isEmpty()) cuts += 1.0
        val k = cuts.size

        val norm = "aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo"
        if (k == 1) {
            val ms = (cuts[0] * 1000).toLong()
            fc.append("[$sfxIdx:a]$norm,adelay=$ms|$ms,apad=whole_dur=$t,asplit=2[sfxmix][sfxsc];")
        } else {
            fc.append("[$sfxIdx:a]$norm,asplit=$k")
            for (i in 0 until k) fc.append("[s$i]")
            fc.append(";")
            cuts.forEachIndexed { i, sec ->
                val ms = (sec * 1000).toLong()
                fc.append("[s$i]adelay=$ms|$ms[d$i];")
            }
            for (i in 0 until k) fc.append("[d$i]")
            fc.append("amix=inputs=$k:duration=longest:dropout_transition=0,volume=$k,")
            fc.append("apad=whole_dur=$t,asplit=2[sfxmix][sfxsc];")
        }

        // Music bed, then duck it whenever the sound effect plays
        fc.append("[$musicIdx:a]$norm,volume=0.6,apad=whole_dur=$t[bg];")
        fc.append("[bg][sfxsc]sidechaincompress=threshold=0.03:ratio=10:attack=20:release=500[ducked];")

        val fadeStart = f((total - 1.5).coerceAtLeast(0.0))
        fc.append("[ducked][sfxmix]amix=inputs=2:duration=first:dropout_transition=0,volume=2,")
        fc.append("afade=t=out:st=$fadeStart:d=1.5[aout]")

        args += listOf(
            "-filter_complex", fc.toString(),
            "-map", "[vout]", "-map", "[aout]",
            "-c:v", "h264_mediacodec", "-b:v", "8M", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart",
            "-t", t,
            out.absolutePath
        )

        return suspendCancellableCoroutine { cont ->
            FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { session ->
                    if (cont.isActive) {
                        val ok = ReturnCode.isSuccess(session.returnCode)
                        cont.resume(ok to (session.output ?: "").takeLast(800))
                    }
                },
                { _ -> },
                { stats -> onProgress((stats.time / 1000.0 / total).coerceIn(0.0, 1.0)) }
            )
        }
    }

    // Copies the finished video into the phone gallery (Movies/AutoCut)
    fun saveToGallery(context: Context, file: File): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/AutoCut")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return "Could not save to gallery"
            context.contentResolver.openOutputStream(uri)?.use { o ->
                file.inputStream().use { it.copyTo(o) }
            }
            file.delete()
            return "Movies/AutoCut/${file.name}"
        }
        return file.absolutePath
    }
}
