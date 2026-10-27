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
        out: File,
        onProgress: (Double) -> Unit
    ): Pair<Boolean, String> {
        val n = clips.size
        val total = clips.sumOf { it.endSec - it.startSec }
        val t = f(total)

        val args = mutableListOf("-y", "-hide_banner")

        // One fresh file handle per clip (a handle can only be read once)
        for (c in clips) {
            val path = FFmpegKitConfig.getSafParameterForRead(context, video.uri)
            args += listOf("-ss", f(c.startSec), "-t", f(c.endSec - c.startSec), "-i", path)
        }
        val musicPath = FFmpegKitConfig.getSafParameterForRead(context, music)
        args += listOf("-i", musicPath)

        val fc = StringBuilder()
        for (i in 0 until n) fc.append("[$i:v]setpts=PTS-STARTPTS[v$i];")
        for (i in 0 until n) fc.append("[v$i]")
        fc.append("concat=n=$n:v=1:a=0[vout];")

        val fadeStart = f((total - 1.5).coerceAtLeast(0.0))
        fc.append("[$n:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,")
        fc.append("volume=0.8,apad,afade=t=out:st=$fadeStart:d=1.5[aout]")

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
