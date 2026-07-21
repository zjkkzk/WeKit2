package dev.ujhhgtg.wekit.utils

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.div
import kotlin.io.path.isRegularFile

object TelegramStickerConverter {
    private const val VIDEO_FPS = 15
    private const val MAX_VIDEO_FRAMES = 90

    fun tgsToGif(input: Path, output: Path): Result<Unit> = runCatching {
        output.parent?.createDirectories()
        tgsToGifNative(input.toString(), output.toString())?.let(::error)
        require(output.isRegularFile() && Files.size(output) > 0L) { "TGS 转换未生成 GIF" }
    }

    suspend fun webmToGif(input: Path, output: Path): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            output.parent?.createDirectories()
            val framesDir = output.resolveSibling("${output.fileName}.frames-${UUID.randomUUID()}")
                .also { it.createDirectories() }
            try {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(input.toString())
                    val durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull()
                        ?.coerceAtLeast(1L)
                        ?: error("无法读取 Telegram 视频表情时长")
                    val frameCount = ((durationMs * VIDEO_FPS + 999L) / 1000L)
                        .toInt()
                        .coerceIn(1, MAX_VIDEO_FRAMES)
                    val durationUs = durationMs * 1_000L
                    repeat(frameCount) { index ->
                        currentCoroutineContext().ensureActive()
                        val timestampUs = ((index + 0.5) * durationUs / frameCount)
                            .toLong()
                            .coerceAtMost((durationUs - 1L).coerceAtLeast(0L))
                        val bitmap = retriever.getFrameAtTime(
                            timestampUs,
                            MediaMetadataRetriever.OPTION_CLOSEST,
                        ) ?: error("无法解码 Telegram 视频表情第 ${index + 1} 帧")
                        try {
                            val framePath = framesDir / "%04d.png".format(index)
                            Files.newOutputStream(framePath).use { stream ->
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                                    "无法写入 Telegram 视频表情帧"
                                }
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    val delayMs = (durationMs / frameCount).toInt().coerceAtLeast(1)
                    pngFramesToGifNative(framesDir.toString(), output.toString(), delayMs)?.let(::error)
                } finally {
                    retriever.release()
                }
                require(output.isRegularFile() && Files.size(output) > 0L) { "视频表情转换未生成 GIF" }
            } finally {
                framesDir.toFile().deleteRecursively()
            }
            Result.success(Unit)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    private external fun tgsToGifNative(inputPath: String, outputPath: String): String?

    private external fun pngFramesToGifNative(
        framesDir: String,
        outputPath: String,
        delayMs: Int,
    ): String?
}
