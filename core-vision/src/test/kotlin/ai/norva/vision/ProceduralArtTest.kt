package ai.norva.vision

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ProceduralArtTest {
    private fun mean(img: RgbImage, shift: Int): Double {
        var s = 0.0
        for (p in img.pixels) s += (p shr shift) and 0xFF
        return s / img.pixels.size
    }

    private fun diff(a: RgbImage, b: RgbImage): Double {
        var s = 0.0
        for (i in a.pixels.indices) {
            s += kotlin.math.abs(((a.pixels[i] shr 16) and 0xFF) - ((b.pixels[i] shr 16) and 0xFF))
            s += kotlin.math.abs(((a.pixels[i] shr 8) and 0xFF) - ((b.pixels[i] shr 8) and 0xFF))
            s += kotlin.math.abs((a.pixels[i] and 0xFF) - (b.pixels[i] and 0xFF))
        }
        return s / (3.0 * a.pixels.size)
    }

    @Test fun sameRequestGivesSamePicture() {
        val a = ProceduralArt.render("закат над городом", 48, 48)
        val b = ProceduralArt.render("закат над городом", 48, 48)
        assertContentEquals(a.pixels, b.pixels)
    }

    @Test fun differentRequestsGiveDifferentPictures() {
        val a = ProceduralArt.render("закат", 48, 48)
        val b = ProceduralArt.render("лес", 48, 48)
        assertTrue(diff(a, b) > 5.0)
    }

    @Test fun sunsetIsWarmAndSeaIsCool() {
        val sunset = ProceduralArt.render("закат", 64, 64)
        val sea = ProceduralArt.render("море", 64, 64)
        assertTrue(mean(sunset, 16) > mean(sunset, 0) + 20, "закат должен быть тёплым")
        assertTrue(mean(sea, 0) > mean(sea, 16) + 20, "море должно быть холодным")
    }

    @Test fun videoLoopsSeamlessly() {
        val start = ProceduralArt.render("гонка", 48, 48, 0f)
        val end = ProceduralArt.render("гонка", 48, 48, 1f)
        val middle = ProceduralArt.render("гонка", 48, 48, 0.5f)
        assertTrue(diff(start, end) < 1.5, "кадры петли различаются: ${diff(start, end)}")
        assertTrue(diff(start, middle) > 3.0, "середина петли не отличается от начала")
    }

    @Test fun outputIsOpaqueAndHasRequestedSize() {
        val img = ProceduralArt.render("что угодно", 40, 30)
        assertTrue(img.width == 40 && img.height == 30)
        assertTrue(img.pixels.all { (it ushr 24) == 0xFF })
    }

    @Test fun unknownTextStillGetsAPalette() {
        val look = ProceduralArt.look("qwerty zzz")
        assertTrue(look.palette.colors.size == 5)
    }

    @Test fun keywordsSwitchOnFeatures() {
        assertTrue(ProceduralArt.look("звёздное небо").stars)
        assertTrue(ProceduralArt.look("гонка").stripes)
        assertTrue(ProceduralArt.look("планета").rings)
    }
}
