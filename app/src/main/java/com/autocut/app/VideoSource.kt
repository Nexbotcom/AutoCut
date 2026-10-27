package com.autocut.app

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.arthenica.ffmpegkit.FFmpegKitConfig

data class VideoInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val ffmpegPath: String
)

object VideoSource {

    fun read(context: Context, uri: Uri): VideoInfo {
        var name = "video"
        var size = -1L

        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0) name = c.getString(n) ?: name
                if (s >= 0) size = c.getLong(s)
            }
        }

        var duration = 0L
        var w = 0
        var h = 0
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            duration = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_DURATION
            )?.toLongOrNull() ?: 0L
            w = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
            )?.toIntOrNull() ?: 0
            h = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
            )?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }

        // Lets FFmpeg stream straight from the picked file, no copying
        val path = FFmpegKitConfig.getSafParameterForRead(context, uri)

        return VideoInfo(uri, name, size, duration, w, h, path)
    }
}
