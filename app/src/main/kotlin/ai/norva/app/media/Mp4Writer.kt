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
class Mp4Writer(private val file: File, private val width: Int, private val height: Int, private val fps: Int, private val exactFrames: Boolean = false) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var surface: Surface? = null
    private var track = -1
    private var muxerStarted = false
    private var submitted = 0L
    private var written = 0L
    private val dest = Rect(0, 0, width, height)

    fun start() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, if (exactFrames) MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                else MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        if (!exactFrames) surface = codec.createInputSurface()
        codec.start()
    }

    /** Queues every generated frame with an explicit timestamp. A Surface can discard
     * frames when an offline producer runs faster than the codec consumes them. */
    fun writePixels(pixels: IntArray) {
        require(exactFrames && pixels.size == width * height)
        val index = inputSlot()
        val image = checkNotNull(codec.getInputImage(index)) { "Кодек не предоставил плоскости YUV" }
        image.use {
            val planes = it.planes
            val buffers = planes.map { plane -> plane.buffer }.toTypedArray()
            val offsets = buffers.map { buffer -> buffer.position() }
            fun put(plane: Int, x: Int, y: Int, value: Int) {
                val offset = offsets[plane] + y * planes[plane].rowStride + x * planes[plane].pixelStride
                buffers[plane].put(offset, value.coerceIn(0, 255).toByte())
            }
            for (y in 0 until height) for (x in 0 until width) {
                val rgb = pixels[y * width + x]
                val r = (rgb ushr 16) and 255
                val g = (rgb ushr 8) and 255
                val b = rgb and 255
                put(0, x, y, ((66*r + 129*g + 25*b + 128) shr 8) + 16)
                if (x % 2 == 0 && y % 2 == 0) {
                    put(1, x/2, y/2, ((-38*r - 74*g + 112*b + 128) shr 8) + 128)
                    put(2, x/2, y/2, ((112*r - 94*g - 18*b + 128) shr 8) + 128)
                }
            }
        }
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, submitted * 1_000_000L / fps, 0)
        submitted++
        drain(false)
    }

    private fun inputSlot(): Int {
        repeat(500) {
            val index = codec.dequeueInputBuffer(10_000L)
            if (index >= 0) return index
            drain(false)
        }
        error("Кодек не предоставил входной буфер")
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
            if (exactFrames) codec.queueInputBuffer(inputSlot(), 0, 0, submitted * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            else codec.signalEndOfInputStream()
            drain(true)
            check(!exactFrames || written == submitted) { "Кодек пропустил кадр" }
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
