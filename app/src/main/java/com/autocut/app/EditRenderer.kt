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

    private const val TD = 0.5 // transition length in seconds
    private val TRANSITIONS = listOf("fade", "slideleft", "zoomin", "fadeblack", "dissolve")

    private fun f(x: Double) = String.format(Locale.US, "%.3f", x)

    suspend fun render(
        context: Context,
        video: VideoInfo,
        clips: List<Highlight>,
        music: Uri,
        out: File,
        onProgress: (Double) -> Unit
    ): Pair<Boolean, String> {
        val use = clips.filter { it.endSec - it.startSec >= 1.5 }
        if (use.isEmpty()) return false to "No clips long enough to edit."

        val n = use.size
        val durs = use.map { it.endSec - it.startSec }
        val total = durs.sum() - (n - 1) * TD
        val t = f(total)

        // Output size: keep source size, cap at 1920, force even numbers
        var w = if (video.width > 0) video.width else 1280
        var h = if (video.height > 0) video.height else 720
        val cap = 1920.0 / maxOf(w, h)
        if (cap < 1.0) { w = (w * cap).toInt(); h = (h * cap).toInt() }
        w = w / 2 * 2
        h = h / 2 * 2

        val args = mutableListOf("-y", "-hide_banner")

        // One fresh file handle per clip (a handle can only be read once)
        use.forEachIndexed { i, c ->
            val path = FFmpegKitConfig.getSafParameterForRead(context, video.uri)
            args += listOf("-ss", f(c.startSec), "-t", f(durs[i]), "-i", path)
        }
        val musicPath = FFmpegKitConfig.getSafParameterForRead(context, music)
        args += listOf("-i", musicPath)

        val fc = StringBuilder()

        // 1) Normalize every clip so they can be blended
        for (i in 0 until n) {
            fc.append("[$i:v]scale=$w:$h:force_original_aspect_ratio=decrease,")
            fc.append("pad=$w:$h:(ow-iw)/2:(oh-ih)/2,setsar=1,fps=30,format=yuv420p,")
            fc.append("setpts=PTS-STARTPTS[v$i];")
        }

        // 2) Chain transitions between clips
        var prev = "v0"
        var cum = durs[0]
        for (k in 1 until n) {
            val offset = cum - k * TD
            val tr = TRANSITIONS[(k - 1) % TRANSITIONS.size]
            fc.append("[$prev][v$k]xfade=transition=$tr:duration=$TD:offset=${f(offset)}[x$k];")
            prev = "x$k"
            cum += durs[k]
        }

        // 3) Cinematic look: color grade, vignette, black bars, fades
        val vFadeOut = f((total - 0.8).coerceAtLeast(0.0))
        fc.append("[$prev]eq=contrast=1.1:saturation=1.2:gamma=0.97,")
        fc.append("colorbalance=rs=0.04:bs=-0.04:rh=0.03:bh=-0.03,")
        fc.append("vignette=angle=PI/5,")
        fc.append("drawbox=x=0:y=0:w=iw:h=ih*0.13:color=black:t=fill,")
        fc.append("drawbox=x=0:y=ih*0.87:w=iw:h=ih*0.13:color=black:t=fill,")
        fc.append("fade=t=in:st=0:d=0.6,fade=t=out:st=$vFadeOut:d=0.8[vout];")

        // 4) Music bed with fade in and out
        val aFadeOut = f((total - 1.5).coerceAtLeast(0.0))
        fc.append("[$n:a]aresample=44100,aformat=sample_fmts=fltp:channel_layouts=stereo,")
        fc.append("volume=0.8,apad=whole_dur=$t,afade=t=in:st=0:d=1,")
        fc.append("afade=t=out:st=$aFadeOut:d=1.5[aout]")

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
            val session = FFmpegKit.executeWithArgumentsAsync(
                args.toTypedArray(),
                { s ->
                    if (cont.isActive) {
                        val ok = ReturnCode.isSuccess(s.returnCode)
                        if (!ok) out.delete() // never leave a half-written file behind
                        cont.resume(ok to (s.output ?: "").takeLast(800))
                    }
                },
                { _ -> },
                { stats -> onProgress((stats.time / 1000.0 / total).coerceIn(0.0, 1.0)) }
            )
            cont.invokeOnCancellation {
                FFmpegKit.cancel(session.sessionId)
                out.delete()
            }
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
