package ai.norva.vision

/** Картинка как массив ARGB-пикселей. Не зависит от Android, поэтому весь код зрения проверяется обычными тестами. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(width > 0 && height > 0) { "Пустое изображение" }
        require(pixels.size == width * height) { "Размер массива пикселей не совпадает с размерами" }
    }

    fun red(i: Int): Int = (pixels[i] shr 16) and 0xFF
    fun green(i: Int): Int = (pixels[i] shr 8) and 0xFF
    fun blue(i: Int): Int = pixels[i] and 0xFF

    /** Уменьшение усреднением по блокам. Если картинка уже не больше maxSide, возвращается она сама. */
    fun downscale(maxSide: Int): RgbImage {
        val side = maxOf(width, height)
        if (side <= maxSide) return this
        val nw = maxOf(1, (width.toLong() * maxSide / side).toInt())
        val nh = maxOf(1, (height.toLong() * maxSide / side).toInt())
        val out = IntArray(nw * nh)
        for (y in 0 until nh) {
            val y0 = (y.toLong() * height / nh).toInt()
            val y1 = maxOf(y0 + 1, ((y + 1).toLong() * height / nh).toInt())
            for (x in 0 until nw) {
                val x0 = (x.toLong() * width / nw).toInt()
                val x1 = maxOf(x0 + 1, ((x + 1).toLong() * width / nw).toInt())
                var r = 0L
                var g = 0L
                var b = 0L
                for (yy in y0 until y1) {
                    for (xx in x0 until x1) {
                        val p = pixels[yy * width + xx]
                        r += (p shr 16) and 0xFF
                        g += (p shr 8) and 0xFF
                        b += p and 0xFF
                    }
                }
                val n = ((y1 - y0) * (x1 - x0)).toLong()
                out[y * nw + x] = (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (b / n).toInt()
            }
        }
        return RgbImage(nw, nh, out)
    }

    companion object {
        fun solid(width: Int, height: Int, rgb: Int): RgbImage =
            RgbImage(width, height, IntArray(width * height) { (0xFF shl 24) or (rgb and 0xFFFFFF) })

        fun build(width: Int, height: Int, color: (x: Int, y: Int) -> Int): RgbImage =
            RgbImage(width, height, IntArray(width * height) { (0xFF shl 24) or (color(it % width, it / width) and 0xFFFFFF) })
    }
}
