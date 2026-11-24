package ai.norva.vision

import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class ColorShare(val name: String, val rgb: Int, val share: Double)

/** Низкоуровневые характеристики кадра. Это не распознавание объектов. */
data class ImageReport(
    val width: Int,
    val height: Int,
    /** 0..1 */
    val brightness: Double,
    /** Стандартное отклонение яркости, 0..0.5 */
    val contrast: Double,
    /** Метрика красочности (разброс и сдвиг цветовых каналов), около 0..110 */
    val colorfulness: Double,
    /** -1 холодный ... +1 тёплый */
    val warmth: Double,
    /** Дисперсия лапласиана яркости: большая у резких кадров, около нуля у гладких */
    val sharpness: Double,
    /** Доля пикселей с сильным градиентом (контуры) */
    val edgeDensity: Double,
    val dominant: List<ColorShare>,
    /** Средняя яркость в сетке 3x3, по строкам, 0..1 */
    val brightGrid: List<Double>,
    /** 1 - идеально симметрично слева направо */
    val symmetry: Double,
)

object ImageAnalyzer {
    private const val ANALYSIS_SIDE = 160
    private const val CLUSTERS = 5
    private const val MAX_SAMPLES = 4096

    private val cellNames = listOf(
        "верхний левый угол", "верх", "верхний правый угол",
        "левая часть", "центр", "правая часть",
        "нижний левый угол", "низ", "нижний правый угол",
    )

    fun analyze(src: RgbImage): ImageReport {
        val img = src.downscale(ANALYSIS_SIDE)
        val w = img.width
        val h = img.height
        val n = w * h
        val luma = DoubleArray(n)
        val rg = DoubleArray(n)
        val yb = DoubleArray(n)
        var sumL = 0.0
        var sumR = 0.0
        var sumB = 0.0
        for (i in 0 until n) {
            val r = img.red(i).toDouble()
            val g = img.green(i).toDouble()
            val b = img.blue(i).toDouble()
            val l = 0.299 * r + 0.587 * g + 0.114 * b
            luma[i] = l
            sumL += l
            sumR += r
            sumB += b
            rg[i] = r - g
            yb[i] = 0.5 * (r + g) - b
        }
        val meanL = sumL / n
        var varL = 0.0
        for (l in luma) varL += (l - meanL) * (l - meanL)
        varL /= n

        val colorfulness = colorfulness(rg, yb)
        val (sharpness, edges) = sharpnessAndEdges(luma, w, h)
        val grid = grid(luma, w, h)

        var asym = 0.0
        for (y in 0 until h) {
            for (x in 0 until w) {
                asym += kotlin.math.abs(luma[y * w + x] - luma[y * w + (w - 1 - x)]) / 255.0
            }
        }
        asym /= n

        return ImageReport(
            width = src.width,
            height = src.height,
            brightness = meanL / 255.0,
            contrast = sqrt(varL) / 255.0,
            colorfulness = colorfulness,
            warmth = (sumR / n - sumB / n) / 255.0,
            sharpness = sharpness,
            edgeDensity = edges,
            dominant = dominantColors(img),
            brightGrid = grid,
            symmetry = 1.0 - asym,
        )
    }

    private fun colorfulness(rg: DoubleArray, yb: DoubleArray): Double {
        val n = rg.size
        val mRg = rg.sum() / n
        val mYb = yb.sum() / n
        var vRg = 0.0
        var vYb = 0.0
        for (i in 0 until n) {
            vRg += (rg[i] - mRg) * (rg[i] - mRg)
            vYb += (yb[i] - mYb) * (yb[i] - mYb)
        }
        vRg /= n
        vYb /= n
        return sqrt(vRg + vYb) + 0.3 * sqrt(mRg * mRg + mYb * mYb)
    }

    private fun sharpnessAndEdges(l: DoubleArray, w: Int, h: Int): Pair<Double, Double> {
        if (w < 3 || h < 3) return 0.0 to 0.0
        var sum = 0.0
        var sumSq = 0.0
        var edges = 0
        var cnt = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = -4.0 * l[i] + l[i - 1] + l[i + 1] + l[i - w] + l[i + w]
                sum += lap
                sumSq += lap * lap
                val gx = (l[i - w + 1] + 2 * l[i + 1] + l[i + w + 1]) - (l[i - w - 1] + 2 * l[i - 1] + l[i + w - 1])
                val gy = (l[i + w - 1] + 2 * l[i + w] + l[i + w + 1]) - (l[i - w - 1] + 2 * l[i - w] + l[i - w + 1])
                if (hypot(gx, gy) > 120.0) edges++
                cnt++
            }
        }
        val mean = sum / cnt
        return (sumSq / cnt - mean * mean) to (edges.toDouble() / cnt)
    }

    private fun grid(l: DoubleArray, w: Int, h: Int): List<Double> {
        val sums = DoubleArray(9)
        val counts = IntArray(9)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val c = (y * 3 / h) * 3 + (x * 3 / w)
                sums[c] += l[y * w + x]
                counts[c]++
            }
        }
        return List(9) { if (counts[it] == 0) 0.0 else sums[it] / counts[it] / 255.0 }
    }

    /** k-means по выборке пикселей: стартовые центры берутся по принципу самой дальней точки, поэтому результат детерминирован. */
    private fun dominantColors(img: RgbImage): List<ColorShare> {
        val n = img.width * img.height
        val stride = maxOf(1, n / MAX_SAMPLES)
        val m = (n + stride - 1) / stride
        val sr = DoubleArray(m)
        val sg = DoubleArray(m)
        val sb = DoubleArray(m)
        for (k in 0 until m) {
            val i = k * stride
            sr[k] = img.red(i).toDouble()
            sg[k] = img.green(i).toDouble()
            sb[k] = img.blue(i).toDouble()
        }
        val cr = DoubleArray(CLUSTERS)
        val cg = DoubleArray(CLUSTERS)
        val cb = DoubleArray(CLUSTERS)
        cr[0] = sr[0]; cg[0] = sg[0]; cb[0] = sb[0]
        for (c in 1 until CLUSTERS) {
            var best = 0
            var bestD = -1.0
            for (k in 0 until m) {
                var nearest = Double.MAX_VALUE
                for (j in 0 until c) nearest = minOf(nearest, dist2(sr[k], sg[k], sb[k], cr[j], cg[j], cb[j]))
                if (nearest > bestD) { bestD = nearest; best = k }
            }
            cr[c] = sr[best]; cg[c] = sg[best]; cb[c] = sb[best]
        }
        val assign = IntArray(m)
        repeat(10) {
            for (k in 0 until m) {
                var bj = 0
                var bd = Double.MAX_VALUE
                for (j in 0 until CLUSTERS) {
                    val d = dist2(sr[k], sg[k], sb[k], cr[j], cg[j], cb[j])
                    if (d < bd) { bd = d; bj = j }
                }
                assign[k] = bj
            }
            val tr = DoubleArray(CLUSTERS)
            val tg = DoubleArray(CLUSTERS)
            val tb = DoubleArray(CLUSTERS)
            val tc = IntArray(CLUSTERS)
            for (k in 0 until m) {
                val j = assign[k]
                tr[j] += sr[k]; tg[j] += sg[k]; tb[j] += sb[k]; tc[j]++
            }
            for (j in 0 until CLUSTERS) {
                if (tc[j] > 0) { cr[j] = tr[j] / tc[j]; cg[j] = tg[j] / tc[j]; cb[j] = tb[j] / tc[j] }
            }
        }
        val counts = IntArray(CLUSTERS)
        for (k in 0 until m) counts[assign[k]]++

        val byName = LinkedHashMap<String, ColorShare>()
        val order = (0 until CLUSTERS).filter { counts[it] > 0 }.sortedByDescending { counts[it] }
        for (j in order) {
            val r = cr[j].roundToInt()
            val g = cg[j].roundToInt()
            val b = cb[j].roundToInt()
            val name = ColorNames.name(r, g, b)
            val share = counts[j].toDouble() / m
            val prev = byName[name]
            byName[name] = if (prev == null) ColorShare(name, (r shl 16) or (g shl 8) or b, share)
            else prev.copy(share = prev.share + share)
        }
        return byName.values.sortedByDescending { it.share }.filter { it.share >= 0.05 }.take(4)
    }

    private fun dist2(r1: Double, g1: Double, b1: Double, r2: Double, g2: Double, b2: Double): Double =
        (r1 - r2) * (r1 - r2) + (g1 - g2) * (g1 - g2) + (b1 - b2) * (b1 - b2)

    /** Текстовое описание кадра для чат-модели и для пользователя. */
    fun describeRu(r: ImageReport): String {
        val orient = when {
            r.width > r.height * 1.1 -> "альбомная"
            r.height > r.width * 1.1 -> "портретная"
            else -> "квадратная"
        }
        val bright = when {
            r.brightness < 0.25 -> "очень тёмное"
            r.brightness < 0.42 -> "тёмное"
            r.brightness < 0.62 -> "средней яркости"
            r.brightness < 0.8 -> "светлое"
            else -> "очень яркое"
        }
        val contrast = when {
            r.contrast < 0.1 -> "низкий"
            r.contrast < 0.22 -> "средний"
            else -> "высокий"
        }
        val saturation = when {
            r.colorfulness < 15 -> "почти бесцветное"
            r.colorfulness < 33 -> "приглушённые цвета"
            r.colorfulness < 59 -> "умеренно насыщенные цвета"
            r.colorfulness < 82 -> "насыщенные цвета"
            else -> "очень насыщенные цвета"
        }
        val tone = when {
            r.warmth > 0.12 -> "тёплая"
            r.warmth < -0.12 -> "холодная"
            else -> "нейтральная"
        }
        val sharp = when {
            r.sharpness < 15 -> "размытое или очень гладкое"
            r.sharpness < 120 -> "умеренная резкость"
            else -> "резкое, с чёткими деталями"
        }
        val detail = when {
            r.edgeDensity < 0.03 -> "мало мелких деталей"
            r.edgeDensity < 0.12 -> "умеренно деталей"
            else -> "много мелких деталей и контуров"
        }
        val colors = r.dominant.joinToString(", ") { "${it.name} (${(it.share * 100).roundToInt()}%)" }
        val sb = StringBuilder()
        sb.append("Кадр ${r.width}×${r.height}, ориентация ${orient}. ")
        sb.append("Общая яркость: $bright, контраст $contrast. ")
        sb.append("Доминирующие цвета: $colors. Палитра: $saturation, цветовая температура $tone. ")
        sb.append("Резкость: $sharp; $detail.")
        val maxI = r.brightGrid.indices.maxByOrNull { r.brightGrid[it] } ?: 0
        val minI = r.brightGrid.indices.minByOrNull { r.brightGrid[it] } ?: 0
        if (r.brightGrid[maxI] - r.brightGrid[minI] > 0.15) {
            sb.append(" Самая светлая область: ${cellNames[maxI]}, самая тёмная: ${cellNames[minI]}.")
        }
        if (r.symmetry > 0.9) sb.append(" Кадр симметричен слева направо.")
        return sb.toString()
    }
}
