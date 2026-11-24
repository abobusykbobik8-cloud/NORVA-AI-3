package ai.norva.vision

/** Название цвета по RGB через оттенок, насыщенность и яркость. Таблица порогов написана для NØRVA Vision. */
object ColorNames {
    fun name(r: Int, g: Int, b: Int): String {
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        val v = mx / 255.0
        val delta = (mx - mn).toDouble()
        val s = if (mx == 0) 0.0 else delta / mx
        if (v < 0.15) return "чёрный"
        if (s < 0.12) {
            return when {
                v > 0.85 -> "белый"
                v > 0.5 -> "светло-серый"
                else -> "серый"
            }
        }
        var h = when (mx) {
            r -> ((g - b) / delta) % 6.0
            g -> (b - r) / delta + 2.0
            else -> (r - g) / delta + 4.0
        } * 60.0
        if (h < 0) h += 360.0
        val base = when {
            h < 15 || h >= 345 -> "красный"
            h < 40 -> "оранжевый"
            h < 68 -> "жёлтый"
            h < 165 -> "зелёный"
            h < 200 -> "бирюзовый"
            h < 255 -> "синий"
            h < 290 -> "фиолетовый"
            else -> "розовый"
        }
        if (v < 0.6 && s > 0.3 && h in 10.0..50.0) return "коричневый"
        if (v < 0.35) return "тёмно-$base"
        if (v > 0.9 && s < 0.5) return "светло-$base"
        return base
    }
}
