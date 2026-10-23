package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbilightControlTest {
    /** A TV whose Ambilight takes calls in order, and where the power-on restores a style of its own (as seen on the device). */
    private class FakeTv(var power: String? = "Off", var config: String? = """{"styleName":"OFF","isExpert":false}""") : AmbilightApi {
        val calls = ArrayList<String>()
        var refuse = false

        override fun power(): String? = power

        override fun config(): String? = config

        override fun setPower(on: Boolean): Boolean {
            calls.add("power " + (if (on) "On" else "Off"))
            if (refuse) return false
            power = if (on) "On" else "Off"
            // the power-on puts its own style back ("FOLLOW_COLOR/HOT_LAVA" on the device) over what was set before it
            if (on) config = """{"styleName":"FOLLOW_COLOR","isExpert":false,"algorithm":"HOT_LAVA"}"""
            return true
        }

        override fun setConfig(json: String): Boolean {
            calls.add("config " + JSONObject(json).optString("styleName"))
            if (refuse) return false
            config = json
            return true
        }
    }

    private class MemoryStore(var saved: AmbilightControl.Saved? = null) : AmbilightStore {
        override fun read(): AmbilightControl.Saved? = saved

        override fun write(saved: AmbilightControl.Saved?) {
            this.saved = saved
        }
    }

    private fun control(tv: FakeTv, store: MemoryStore) = AmbilightControl(tv, store)

    /** The style of the music. */
    private val audio = AmbilightControl.styleJson("FOLLOW_AUDIO", "ENERGY_ADAPTIVE_BRIGHTNESS")

    /** The mood light, Ocean at 80 %. */
    private val ocean = MoodLight.json("#2f80ff", 80)

    /** The lights for the music (what entering Synced mode was before there was a mood light). */
    private fun AmbilightControl.enter() = hold(audio)

    @Test
    fun enteringSwitchesThePowerOnFirstAndThenSetsTheStyle() {
        val tv = FakeTv()
        val store = MemoryStore()
        val c = control(tv, store)
        assertTrue(c.enter())
        assertEquals(listOf("power On", "config FOLLOW_AUDIO"), tv.calls)
        val o = JSONObject(tv.config!!)
        assertEquals("FOLLOW_AUDIO", o.getString("styleName"))
        assertEquals(false, o.getBoolean("isExpert"))
        assertEquals("ENERGY_ADAPTIVE_BRIGHTNESS", o.getString("menuSetting"))
        assertTrue(c.entered)
    }

    @Test
    fun theStateBeforeIsRememberedInTheStoreBeforeAnythingChanges() {
        val tv = FakeTv(power = "On", config = """{"styleName":"FOLLOW_VIDEO","isExpert":false,"menuSetting":"STANDARD"}""")
        val store = MemoryStore()
        val c = control(tv, store)
        c.enter()
        assertEquals("On", store.saved!!.power)
        assertEquals("FOLLOW_VIDEO", AmbilightControl.styleName(store.saved!!.config))
        assertEquals("On", c.remembered!!.power)
    }

    @Test
    fun leavingPutsBackAnAmbilightThatWasOnWithItsConfigurationAfterThePower() {
        val tv = FakeTv(power = "On", config = """{"styleName":"FOLLOW_VIDEO","isExpert":false,"menuSetting":"STANDARD"}""")
        val store = MemoryStore()
        val c = control(tv, store)
        c.enter()
        tv.calls.clear()
        c.leave()
        assertEquals(listOf("power On", "config FOLLOW_VIDEO"), tv.calls)
        assertEquals("FOLLOW_VIDEO", JSONObject(tv.config!!).getString("styleName"))
        assertNull(store.saved)
        assertNull(c.remembered)
        assertFalse(c.entered)
    }

    @Test
    fun leavingSwitchesThePowerOffIfItWasOffOrTheStyleWasOff() {
        // the power was off
        val a = FakeTv(power = "Off", config = """{"styleName":"FOLLOW_COLOR","isExpert":false}""")
        val ca = control(a, MemoryStore())
        ca.enter()
        a.calls.clear()
        ca.leave()
        assertEquals(listOf("power Off"), a.calls)
        assertEquals("Off", a.power)
        // the power was on but the style was OFF
        val b = FakeTv(power = "On", config = """{"styleName":"OFF","isExpert":false}""")
        val cb = control(b, MemoryStore())
        cb.enter()
        b.calls.clear()
        cb.leave()
        assertEquals(listOf("power Off"), b.calls)
    }

    @Test
    fun aTvThatDoesNotAnswerIsLeftAloneAndNothingIsRemembered() {
        val tv = FakeTv(power = null, config = null)
        val store = MemoryStore()
        val c = control(tv, store)
        assertFalse(c.enter())
        assertTrue(tv.calls.isEmpty())
        assertNull(store.saved)
        assertFalse(c.entered)
        c.leave() // nothing to put back
        assertTrue(tv.calls.isEmpty())
    }

    @Test
    fun enteringTwiceChangesNothingTheSecondTime() {
        val tv = FakeTv()
        val c = control(tv, MemoryStore())
        c.enter()
        tv.calls.clear()
        assertTrue(c.enter())
        assertTrue(tv.calls.isEmpty())
    }

    @Test
    fun aCrashInSyncedModeIsPutRightAtTheNextStart() {
        // the first run entered, remembered the lights as On / FOLLOW_VIDEO and died; the second run finds the store
        val store = MemoryStore()
        val tv1 = FakeTv(power = "On", config = """{"styleName":"FOLLOW_VIDEO","isExpert":false}""")
        control(tv1, store).enter()
        assertEquals("On", store.saved!!.power)
        val tv2 = tv1 // the TV is as the crashed run left it: on, FOLLOW_AUDIO
        tv2.calls.clear()
        val second = control(tv2, store)
        second.recover()
        assertEquals(listOf("power On", "config FOLLOW_VIDEO"), tv2.calls)
        assertNull(store.saved)
    }

    @Test
    fun aCrashDoesNotOverwriteTheOriginalStateWithTheSyncedOne() {
        val store = MemoryStore(AmbilightControl.Saved("Off", """{"styleName":"OFF","isExpert":false}"""))
        val tv = FakeTv(power = "On", config = """{"styleName":"FOLLOW_AUDIO","isExpert":false}""")
        val c = control(tv, store)
        c.enter() // an entry before the recovery keeps what was remembered in the end
        assertEquals("Off", store.saved!!.power)
        c.leave()
        assertEquals("Off", tv.power)
        assertNull(store.saved)
    }

    @Test
    fun aRestoreThatTheTvRefusesIsKeptForALaterTry() {
        val tv = FakeTv(power = "On", config = """{"styleName":"FOLLOW_VIDEO","isExpert":false}""")
        val store = MemoryStore()
        val c = control(tv, store)
        c.enter()
        tv.refuse = true
        c.leave()
        assertEquals("kept for the next start", "On", store.saved!!.power)
        assertFalse(c.lastActionOk)
        tv.refuse = false
        c.recover()
        assertNull(store.saved)
    }

    @Test
    fun theStyleCanBeChangedWhileTheLightsAreHeld() {
        val tv = FakeTv()
        val c = control(tv, MemoryStore())
        c.enter()
        tv.calls.clear()
        c.hold(AmbilightControl.styleJson("FOLLOW_VIDEO", "STANDARD"))
        assertEquals(listOf("config FOLLOW_VIDEO"), tv.calls)
    }

    @Test
    fun fromTheMusicToTheMoodLightIsOneConfigurationAndTheStateBeforeBothIsKept() {
        val tv = FakeTv(power = "On", config = """{"styleName":"FOLLOW_COLOR","isExpert":false,"menuSetting":"HOT_LAVA"}""")
        val store = MemoryStore()
        val c = control(tv, store)
        c.hold(audio)
        tv.calls.clear()
        c.hold(ocean)
        assertEquals(listOf("config FOLLOW_COLOR"), tv.calls)
        assertEquals(ocean, c.held)
        // a brighter or other colour is again only the configuration
        tv.calls.clear()
        c.hold(MoodLight.json("#2f80ff", 40))
        assertEquals(listOf("config FOLLOW_COLOR"), tv.calls)
        // what is put back is Hot Lava, from before the music
        assertEquals("FOLLOW_COLOR", AmbilightControl.styleName(store.saved!!.config))
        tv.calls.clear()
        c.leave()
        assertEquals(listOf("power On", "config FOLLOW_COLOR"), tv.calls)
        assertEquals("HOT_LAVA", JSONObject(tv.config!!).getString("menuSetting"))
        assertNull(store.saved)
    }

    @Test
    fun theSameConfigurationIsNotSentAgainUnlessForced() {
        val tv = FakeTv()
        val c = control(tv, MemoryStore())
        c.hold(ocean)
        tv.calls.clear()
        c.hold(ocean)
        assertTrue(tv.calls.isEmpty())
        // forced (a tap on the same mood, the TV back after a time away): the power and the configuration again
        c.hold(ocean, force = true)
        assertEquals(listOf("power On", "config FOLLOW_COLOR"), tv.calls)
    }

    @Test
    fun aConfigurationTheTvRefusedIsSentAgainWithThePowerFirst() {
        val tv = FakeTv()
        val store = MemoryStore()
        val c = control(tv, store)
        tv.refuse = true
        assertTrue(c.hold(ocean))
        assertNull(c.held)
        assertFalse(c.lastActionOk)
        assertTrue("the state before is remembered all the same", store.saved != null)
        tv.refuse = false
        tv.calls.clear()
        c.hold(ocean)
        assertEquals(listOf("power On", "config FOLLOW_COLOR"), tv.calls)
        assertEquals(ocean, c.held)
        assertTrue(c.lastActionOk)
    }

    @Test
    fun aDescriptionNamesTheStyleOrTheColour() {
        assertEquals("FOLLOW_AUDIO ENERGY_ADAPTIVE_BRIGHTNESS", AmbilightControl.describe(audio))
        assertEquals("colour h153 s208 b204", AmbilightControl.describe(ocean))
        assertEquals("?", AmbilightControl.describe("not json"))
    }

    @Test
    fun thePlanOfARestoreIsPowerOffForOffAndPowerOnThenConfigurationForOn() {
        val off = AmbilightControl.plan(AmbilightControl.Saved("Off", """{"styleName":"FOLLOW_VIDEO"}"""))
        assertEquals(1, off.size)
        assertEquals(false, (off[0] as AmbilightControl.Step.Power).on)
        val on = AmbilightControl.plan(AmbilightControl.Saved("On", """{"styleName":"FOLLOW_VIDEO","isExpert":false}"""))
        assertEquals(2, on.size)
        assertEquals(true, (on[0] as AmbilightControl.Step.Power).on)
        assertEquals("""{"styleName":"FOLLOW_VIDEO","isExpert":false}""", (on[1] as AmbilightControl.Step.Config).json)
        // a configuration nobody can read is not "off": it is put back as it is
        val odd = AmbilightControl.plan(AmbilightControl.Saved("On", "not json"))
        assertEquals(2, odd.size)
    }
}
