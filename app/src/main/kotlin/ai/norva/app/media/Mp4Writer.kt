package ai.norva.app.media

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.view.Surface
import java.io.File

/**
 * Запись кадров в MP4 (H.264) через платформенные MediaCodec и MediaMuxer.
 * Кадры рисуются на входную поверхность кодека, а метки времени выставляются по номеру кадра, поэтому скорость
 * воспроизведения не зависит от скорости генерации. Это допущение верно, пока кодек не переупорядочивает кадры (B-кадров нет).
 */
class Mp4Writer(private val file: File, private val width: Int, private val height: Int, private val fps: Int) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var surface: Surface? = null
    private var track = -1
    private var muxerStarted = false
    private var written = 0L
    private val dest = Rect(0, 0, width, height)

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    fun writeFrame(bitmap: Bitmap) {
        val s = checkNotNull(surface) { "Mp4Writer.start() не вызван" }
        val canvas = s.lockHardwareCanvas()
        try {
            canvas.drawBitmap(bitmap, null, dest, null)
        } finally {
            s.unlockCanvasAndPost(canvas)
        }
        drain(false)
    }

    /** Завершает запись и закрывает файл. */
    fun finish() {
        try {
            codec.signalEndOfInputStream()
            drain(true)
        } finally {
            release()
        }
    }

    /** Закрывает ресурсы после ошибки или отмены; недописанный файл удаляется. */
    fun abort() {
        release()
        file.delete()
    }

    private fun drain(untilEnd: Boolean) {
        var idleRounds = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!untilEnd) return
                    if (++idleRounds > 500) error("Кодек не завершил запись видео")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    idleRounds = 0
                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        info.presentationTimeUs = written * 1_000_000L / fps
                        written++
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { if (muxerStarted) muxer.stop() }
        runCatching { muxer.release() }
        runCatching { surface?.release() }
    }
}
