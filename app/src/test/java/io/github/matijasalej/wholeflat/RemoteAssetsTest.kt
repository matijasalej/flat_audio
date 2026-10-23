package io.github.matijasalej.wholeflat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone page and what the home screen of the phone needs: the page links the manifest and the icon and says that it is an app, the
 * manifest says where it starts and has its icons, and the icons are PNGs of the sizes that are named. The page has the three displays, the
 * volume of the kitchen and the mood light, and nothing else (no mode buttons, no fine sync: that is on the debug page).
 */
class RemoteAssetsTest {
    private fun dir(): File {
        val candidates = listOf("app/src/main/assets/remote", "src/main/assets/remote", "../app/src/main/assets/remote")
        return candidates.map { File(it) }.firstOrNull { it.isDirectory } ?: throw AssertionError("assets/remote not found from ${File(".").absolutePath}")
    }

    private fun text(name: String): String = File(dir(), name).readText()

    private fun pngSize(name: String): Pair<Int, Int> {
        val b = File(dir(), name).readBytes()
        // the PNG signature, then the IHDR chunk: width and height as 4 bytes each
        assertEquals(0x89, b[0].toInt() and 0xFF)
        assertEquals("PNG", String(b, 1, 3, Charsets.US_ASCII))
        fun int(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        return Pair(int(16), int(20))
    }

    @Test
    fun thePageIsAnAppForTheHomeScreenOfAnIphone() {
        val html = text("index.html")
        assertTrue(html.contains("""<meta name="apple-mobile-web-app-capable" content="yes">"""))
        assertTrue(html.contains("""<meta name="apple-mobile-web-app-title" content="Whole Flat">"""))
        assertTrue(html.contains("""<link rel="apple-touch-icon" href="/remote/apple-touch-icon.png">"""))
        assertTrue(html.contains("""<link rel="manifest" href="/remote/manifest.webmanifest">"""))
        assertTrue(html.contains("<title>Whole Flat</title>"))
        assertTrue(html.contains("width=device-width"))
    }

    @Test
    fun thePageHasTheThreeDisplaysTheKitchenVolumeAndTheMoodLightAndNothingElse() {
        val html = text("index.html")
        for (d in listOf("listen", "ambilight", "screen")) assertTrue(d, html.contains("""data-display="$d""""))
        assertTrue(html.contains("Listening"))
        assertTrue(html.contains("Screen + Ambilight"))
        assertTrue(html.contains("""type="range""""))
        assertTrue(html.contains("/remote/action?display="))
        assertTrue(html.contains("/remote/action?volume="))
        // the mood light: the moods come from the state of the box (and are made once), a custom colour, the brightness, off
        assertTrue(html.contains("""id="swatches""""))
        assertTrue(html.contains("s.palette"))
        assertTrue(html.contains("'light='"))
        assertTrue(html.contains("'color='"))
        assertTrue(html.contains("'level='"))
        assertTrue(html.contains("""id="lightOff""""))
        // no buttons for the mode, no fine sync, no lyrics: they are not on this page
        assertFalse(html.contains("data-mode"))
        assertFalse(html.contains("mode=synced"))
        assertFalse(html.contains("fine="))
        assertFalse(html.contains("tv_extra"))
        assertFalse(html.contains("lyrics="))
        assertEquals(3, Regex("""class="choice"""").findAll(html).count())
        // the volume of the kitchen, the brightness, and the two sliders of the custom colour
        assertEquals(4, Regex("""type="range"""").findAll(html).count())
    }

    @Test
    fun theManifestSaysWhereItStartsAndHasTheIcons() {
        val m = JSONObject(text("manifest.webmanifest"))
        assertEquals("Whole Flat", m.getString("name"))
        assertEquals("/remote", m.getString("start_url"))
        assertEquals("standalone", m.getString("display"))
        val icons = m.getJSONArray("icons")
        val sizes = (0 until icons.length()).map { icons.getJSONObject(it).getString("sizes") }
        assertTrue(sizes.containsAll(listOf("192x192", "512x512", "180x180")))
        for (i in 0 until icons.length()) {
            val src = icons.getJSONObject(i).getString("src")
            assertTrue(src, File(dir(), src.removePrefix("/remote/")).isFile)
        }
    }

    @Test
    fun theIconsArePngsOfTheNamedSizes() {
        assertEquals(Pair(180, 180), pngSize("apple-touch-icon.png"))
        assertEquals(Pair(192, 192), pngSize("icon-192.png"))
        assertEquals(Pair(512, 512), pngSize("icon-512.png"))
    }

    @Test
    fun theFontsOfThePictureAreThereWithTheirLicense() {
        val fonts = File(dir().parentFile, "fonts")
        for (f in listOf("Figtree-ExtraBold.ttf", "Figtree-SemiBold.ttf", "Figtree-Medium.ttf")) assertTrue(f, File(fonts, f).length() > 20_000)
        val license = File(fonts, "OFL-Figtree.txt").readText()
        assertTrue(license.contains("SIL OPEN FONT LICENSE Version 1.1"))
        assertTrue(license.contains("Figtree Project Authors"))
    }
}
