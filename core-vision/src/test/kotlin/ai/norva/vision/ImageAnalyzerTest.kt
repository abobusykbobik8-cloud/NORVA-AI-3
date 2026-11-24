package ai.norva.vision

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImageAnalyzerTest {
    @Test fun solidRedIsRedAndWarm() {
        val r = ImageAnalyzer.analyze(RgbImage.solid(64, 64, 0xFF0000))
        assertEquals("красный", r.dominant.first().name)
        assertTrue(r.dominant.first().share > 0.95)
        assertTrue(r.warmth > 0.5)
    }

    @Test fun orangeIsWarmAndBlueIsCool() {
        assertTrue(ImageAnalyzer.analyze(RgbImage.solid(32, 32, 0xFF8000)).warmth > 0.3)
        assertTrue(ImageAnalyzer.analyze(RgbImage.solid(32, 32, 0x0000FF)).warmth < -0.3)
    }

    @Test fun whiteAndBlackHalvesHaveHighContrastAndNoSymmetry() {
        val img = RgbImage.build(64, 64) { x, _ -> if (x < 32) 0xFFFFFF else 0x000000 }
        val r = ImageAnalyzer.analyze(img)
        assertTrue(r.contrast > 0.4)
        assertTrue(r.symmetry < 0.05)
        assertTrue(r.brightGrid[0] > 0.95)
        assertTrue(r.brightGrid[2] < 0.05)
    }

    @Test fun checkerboardIsSharpAndGradientIsNot() {
        val checker = RgbImage.build(64, 64) { x, y -> if ((x / 4 + y / 4) % 2 == 0) 0xFFFFFF else 0x000000 }
        val gradient = RgbImage.build(64, 64) { x, _ -> val v = x * 4; (v shl 16) or (v shl 8) or v }
        val c = ImageAnalyzer.analyze(checker)
        val g = ImageAnalyzer.analyze(gradient)
        assertTrue(c.sharpness > 1000, "резкость шахматки ${c.sharpness}")
        assertTrue(g.sharpness < 5, "резкость градиента ${g.sharpness}")
        assertTrue(c.edgeDensity > 0.3)
        assertTrue(g.edgeDensity < 0.001)
    }

    @Test fun twoColoursAreBothReported() {
        val img = RgbImage.build(64, 64) { x, _ -> if (x < 32) 0xFF0000 else 0x0000FF }
        val names = ImageAnalyzer.analyze(img).dominant.map { it.name }
        assertTrue("красный" in names && "синий" in names, "цвета: $names")
    }

    @Test fun descriptionMentionsSizeColourAndOrientation() {
        val text = ImageAnalyzer.describeRu(ImageAnalyzer.analyze(RgbImage.solid(64, 64, 0xFF0000)))
        assertTrue("64×64" in text && "красный" in text && "квадратная" in text, text)
    }

    @Test fun downscaleAveragesBlocks() {
        val img = RgbImage.build(100, 50) { x, _ -> if (x < 50) 0xFF0000 else 0x0000FF }
        val small = img.downscale(20)
        assertEquals(20, small.width)
        assertEquals(10, small.height)
        assertEquals(255, small.red(0))
        assertEquals(255, small.blue(19))
    }

    @Test fun smallImageIsNotResized() {
        val img = RgbImage.solid(10, 10, 0x123456)
        assertTrue(img.downscale(160) === img)
    }

    @Test fun colourNamesCoverBasicCases() {
        assertEquals("чёрный", ColorNames.name(5, 5, 5))
        assertEquals("белый", ColorNames.name(250, 250, 250))
        assertEquals("зелёный", ColorNames.name(0, 200, 0))
        assertEquals("синий", ColorNames.name(0, 0, 255))
        assertEquals("жёлтый", ColorNames.name(255, 255, 0))
    }
}
