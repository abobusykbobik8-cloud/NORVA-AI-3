package ai.norva.app.media

import ai.norva.vision.RgbImage
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/** Мост между Android-картинками и собственным форматом RgbImage. */
object ImageIo {
    /** ImageDecoder сам учитывает поворот из EXIF, поэтому снимки с камеры приходят в правильной ориентации. */
    fun decode(context: Context, uri: Uri, maxSide: Int): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val side = maxOf(info.size.width, info.size.height)
            if (side > maxSide) decoder.setTargetSampleSize((side + maxSide - 1) / maxSide)
        }
    }

    /** Адрес файла для системной камеры: FileProvider выдаёт ей право записи в наш кэш. */
    fun newCaptureUri(context: Context): Uri {
        val dir = File(context.cacheDir, "captures").apply { mkdirs() }
        val file = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun toRgb(bitmap: Bitmap): RgbImage {
        val px = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(px, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return RgbImage(bitmap.width, bitmap.height, px)
    }

    fun toBitmap(img: RgbImage): Bitmap =
        Bitmap.createBitmap(img.pixels, img.width, img.height, Bitmap.Config.ARGB_8888)

    fun saveJpeg(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    fun savePng(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
