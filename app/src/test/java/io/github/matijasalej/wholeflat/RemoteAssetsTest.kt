package io.github.matijasalej.wholeflat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone page and what the home screen of the phone needs: the page links the manifest and the icon and says that it is an app, the
 * manifest says where it starts and has its icons, and the icons are PNGs of the sizes that are named. The page has the switch of Synced on
 * HDMI, the four displays with the colour of Chill, the volume and the timing of the kitchen and the less, just right or more colour of the
 * mood lights, and nothing else (no mode buttons, no TV extra, no lyrics: they are on the debug page).
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
    fun thePageHasTheSwitchTheFourDisplaysTheChillColourAndTheKitchen() {
        val html = text("index.html")
        for (d in listOf("listen", "chill", "party", "karaoke")) assertTrue(d, html.contains("""data-display="$d""""))
        assertEquals(4, Regex("""class="choice"""").findAll(html).count())
        assertTrue(html.contains("Synced on HDMI"))
        assertTrue(html.contains("""role="switch""""))
        assertTrue(html.contains("/remote/action?replay="))
        assertTrue(html.contains("/remote/action?display="))
        assertTrue(html.contains("/remote/action?volume="))
        assertTrue(html.contains("'color='"))
        assertTrue(html.contains("'level='"))
        assertTrue(html.contains("'fine_ms='"))
        assertTrue(html.contains("'pulse='"))
        assertTrue(html.contains("Slow pulse"))
        // the volume, the custom hue, the brightness and the kitchen's timing
        assertEquals(4, Regex("""type="range"""").findAll(html).count())
        // no buttons for the mode, no TV extra, no lyrics: they are on the debug page
        assertFalse(html.contains("data-mode"))
        assertFalse(html.contains("mode=synced"))
        assertFalse(html.contains("tv_extra"))
        assertFalse(html.contains("lyrics="))
        assertTrue(html.contains("/lights/colour?step="))
        assertEquals(3, Regex("""class="colour"""").findAll(html).count())
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
