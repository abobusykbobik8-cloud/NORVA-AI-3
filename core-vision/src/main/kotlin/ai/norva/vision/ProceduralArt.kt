package ai.norva.vision

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Целочисленный хеш для шума и выбора палитры. */
object Hash {
    fun text(s: String): Long {
        var h = 1469598103L
        for (c in s) {
            h = (h xor c.code.toLong()) * 1099511L + (h ushr 7)
            h = h xor (h shl 13)
        }
        return h
    }

    fun cell(ix: Int, iy: Int, seed: Int): Float {
        var h = ix * 374761393 + iy * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777215f
    }
}

/** Гладкий шум по значениям в узлах решётки. */
class ValueNoise(private val seed: Int) {
    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    fun noise(x: Float, y: Float): Float {
        val x0 = floor(x).toInt()
        val y0 = floor(y).toInt()
        val fx = smooth(x - x0)
        val fy = smooth(y - y0)
        val a = Hash.cell(x0, y0, seed)
        val b = Hash.cell(x0 + 1, y0, seed)
        val c = Hash.cell(x0, y0 + 1, seed)
        val d = Hash.cell(x0 + 1, y0 + 1, seed)
        val top = a + (b - a) * fx
        val bottom = c + (d - c) * fx
        return top + (bottom - top) * fy
    }

    fun fbm(x: Float, y: Float, octaves: Int = 3): Float {
        var amp = 0.5f
        var freq = 1f
        var sum = 0f
        var norm = 0f
        repeat(octaves) {
            sum += amp * noise(x * freq, y * freq)
            norm += amp
            amp *= 0.5f
            freq *= 2f
        }
        return sum / norm
    }
}

class Palette(val colors: IntArray) {
    /** t в 0..1, результат 0xRRGGBB */
    fun sample(t: Float): Int {
        val p = t.coerceIn(0f, 1f) * (colors.size - 1)
        val i = minOf(p.toInt(), colors.size - 2)
        return mix(colors[i], colors[i + 1], p - i)
    }

    companion object {
        fun mix(a: Int, b: Int, t: Float): Int {
            val r = (((a shr 16) and 0xFF) * (1 - t) + ((b shr 16) and 0xFF) * t).toInt()
            val g = (((a shr 8) and 0xFF) * (1 - t) + ((b shr 8) and 0xFF) * t).toInt()
            val bl = ((a and 0xFF) * (1 - t) + (b and 0xFF) * t).toInt()
            return (r shl 16) or (g shl 8) or bl
        }
    }
}

/** Что именно нарисует генератор: палитра, узор и параметры, выведенные из текста запроса. */
class Look(
    val palette: Palette,
    val stars: Boolean,
    val rings: Boolean,
    val stripes: Boolean,
    val seed: Int,
    val scale: Float,
)

/**
 * Процедурный генератор картинок и кадров видео NØRVA Canvas.
 * Это НЕ нейросеть: смысл запроса не понимается. Текст превращается в палитру (по ключевым словам) и в число для шума,
 * поэтому один и тот же запрос всегда даёт одну и ту же картинку.
 * Время time в 0..1 образует замкнутую петлю: кадр при 0 и при 1 совпадает.
 */
object ProceduralArt {
    private class Theme(val stems: List<String>, val colors: IntArray)

    private val themes = listOf(
        Theme(listOf("закат", "рассвет", "солнц", "sunset", "sunrise"), intArrayOf(0x2B1055, 0xB3205F, 0xFF6B35, 0xFFC857, 0xFFE8A3)),
        Theme(listOf("ночь", "ночн", "космос", "звезд", "звёзд", "галакт", "space", "night", "star"), intArrayOf(0x02030F, 0x0B1F5C, 0x4B2A9A, 0x1AA7EC, 0xBFEFFF)),
        Theme(listOf("лес", "природ", "трав", "зелен", "зелён", "forest", "green", "nature"), intArrayOf(0x07200F, 0x145A32, 0x2E8B57, 0x8FD18F, 0xE6F5C9)),
        Theme(listOf("мор", "океан", "вод", "волн", "sea", "ocean", "water", "wave"), intArrayOf(0x021B3A, 0x0B5394, 0x1E88E5, 0x4DD0E1, 0xE0FBFF)),
        Theme(listOf("огон", "огн", "пламя", "лава", "красн", "fire", "lava"), intArrayOf(0x1A0500, 0x7A1008, 0xD93A0B, 0xFF9A1F, 0xFFE27A)),
        Theme(listOf("гонк", "скорост", "формул", "машин", "болид", "racing", "speed", "bull", "редбулл"), intArrayOf(0x070C24, 0x14307A, 0x2F5BFF, 0xE10A3A, 0xFFC21A)),
        Theme(listOf("лёд", "лед", "снег", "зим", "ice", "snow", "winter"), intArrayOf(0x0B2545, 0x3D7EAA, 0x9AD1F5, 0xDDF3FF, 0xFFFFFF)),
        Theme(listOf("неон", "кибер", "будущ", "футур", "neon", "cyber"), intArrayOf(0x0A0020, 0x5B00B5, 0xFF2BD6, 0x00E5FF, 0xE8FFFF)),
    )

    fun look(prompt: String): Look {
        val text = prompt.lowercase()
        val seedLong = Hash.text(text)
        val seed = (seedLong xor (seedLong ushr 32)).toInt()
        val theme = themes.firstOrNull { t -> t.stems.any { text.contains(it) } }
        val palette = if (theme != null) Palette(theme.colors) else Palette(hashedColors(seed))
        val racing = theme != null && theme.colors[3] == 0xE10A3A
        val space = theme != null && theme.colors[0] == 0x02030F
        return Look(
            palette = palette,
            stars = space || listOf("звезд", "звёзд", "star").any { text.contains(it) },
            rings = listOf("круг", "кольц", "планет", "солнц", "ring", "circle", "planet", "sun").any { text.contains(it) },
            stripes = racing || listOf("полос", "stripe").any { text.contains(it) },
            seed = seed,
            scale = 2.2f + (seed and 0xFF) / 255f * 2f,
        )
    }

    private fun hashedColors(seed: Int): IntArray {
        val hue = (seed and 0x7FFFFFFF) % 360
        val values = floatArrayOf(0.12f, 0.35f, 0.65f, 0.9f, 1.0f)
        return IntArray(5) { hsv((hue + it * 35) % 360f, 0.8f, values[it]) }
    }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val x = c * (1 - abs((h / 60f) % 2 - 1))
        val m = v - c
        val (r, g, b) = when {
            h < 60 -> Triple(c, x, 0f)
            h < 120 -> Triple(x, c, 0f)
            h < 180 -> Triple(0f, c, x)
            h < 240 -> Triple(0f, x, c)
            h < 300 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return (((r + m) * 255).toInt() shl 16) or (((g + m) * 255).toInt() shl 8) or ((b + m) * 255).toInt()
    }

    fun render(prompt: String, width: Int, height: Int, time: Float = 0f): RgbImage =
        render(look(prompt), width, height, time)

    fun render(look: Look, width: Int, height: Int, time: Float): RgbImage {
        val noise = ValueNoise(look.seed)
        val ang = (time * 2.0 * PI).toFloat()
        val ox = 0.45f * cos(ang)
        val oy = 0.45f * sin(ang)
        val aspect = height / width.toFloat()
        val pal = look.palette
        val glowColors = IntArray(3) { pal.colors[(it + 2) % pal.colors.size] }
        val bx = FloatArray(3) { 0.5f + 0.30f * cos(ang + it * 2.1f) }
        val by = FloatArray(3) { 0.5f + 0.30f * sin(2f * ang + it * 2.1f) }
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val v = y / height.toFloat()
            for (x in 0 until width) {
                val u = x / width.toFloat()
                val nx = u * look.scale
                val ny = v * aspect * look.scale
                val qx = noise.fbm(nx + ox, ny + oy)
                val qy = noise.fbm(nx + 5.2f - oy, ny + 1.3f + ox)
                val n = noise.fbm(nx + 2.6f * qx + 0.3f * ox, ny + 2.6f * qy + 0.3f * oy, 4)
                val base = pal.sample((n - 0.2f) / 0.6f)
                var r = ((base shr 16) and 0xFF).toFloat()
                var g = ((base shr 8) and 0xFF).toFloat()
                var b = (base and 0xFF).toFloat()

                for (i in 0 until 3) {
                    val dx = u - bx[i]
                    val dy = (v - by[i]) * aspect
                    val glow = exp(-(dx * dx + dy * dy) * 14f) * 0.45f
                    r += ((glowColors[i] shr 16) and 0xFF) * glow
                    g += ((glowColors[i] shr 8) and 0xFF) * glow
                    b += (glowColors[i] and 0xFF) * glow
                }
                if (look.rings) {
                    val d = sqrt((u - 0.5f) * (u - 0.5f) + (v - 0.5f) * (v - 0.5f) * aspect * aspect)
                    val k = 0.88f + 0.12f * sin(d * 38f - 2f * ang)
                    r *= k; g *= k; b *= k
                }
                if (look.stripes) {
                    val band = (u * 0.8f + v * 0.6f) * 7f - time
                    if (band - floor(band) < 0.035f) {
                        r = r * 0.5f + 127f; g = g * 0.5f + 127f; b = b * 0.5f + 127f
                    }
                }
                if (look.stars && x % 3 == 1 && y % 3 == 1) {
                    val h = Hash.cell(x / 3, y / 3, look.seed + 17)
                    if (h > 0.985f) {
                        val tw = 0.6f + 0.4f * sin(ang + h * 40f)
                        r += 230f * tw; g += 230f * tw; b += 230f * tw
                    }
                }
                val dd = (u - 0.5f) * (u - 0.5f) + (v - 0.5f) * (v - 0.5f)
                val vignette = 1f - 0.9f * dd
                val grain = (Hash.cell(x, y, look.seed + 99) - 0.5f) * 8f
                val ri = (r * vignette + grain).toInt().coerceIn(0, 255)
                val gi = (g * vignette + grain).toInt().coerceIn(0, 255)
                val bi = (b * vignette + grain).toInt().coerceIn(0, 255)
                pixels[y * width + x] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
            }
        }
        return RgbImage(width, height, pixels)
    }
}
