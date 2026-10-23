package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class KitchenVolumeTest {
    @Test
    fun aCapturedStreamGetsTheKitchenVolumeOfTheSetting() {
        assertEquals(35, KitchenVolume.sonos(capture = true, kitchenVolume = 35, mirrorOn = false, mirrorRaw = -1, connectRaw = 40_000, balance = 3))
        assertEquals(0, KitchenVolume.sonos(true, 0, false, -1, -1, 0))
        assertEquals(100, KitchenVolume.sonos(true, 100, false, -1, -1, 0))
        // the balance is for the volume that follows the phone, not for the one that is set
        assertEquals(35, KitchenVolume.sonos(true, 35, false, -1, -1, -20))
    }

    @Test
    fun withoutASettingTheSonosIsLeftAloneOnABoxWhereCastChangesNothing() {
        assertEquals(-1, KitchenVolume.sonos(capture = true, kitchenVolume = -1, mirrorOn = false, mirrorRaw = 30_000, connectRaw = 40_000, balance = 0))
    }

    @Test
    fun theSettingWinsOverTheMirror() {
        assertEquals(20, KitchenVolume.sonos(true, 20, true, 65_535, -1, 0))
    }

    @Test
    fun whereTheMirrorIsOnTheSonosFollowsTheBoxWithTheBalance() {
        assertEquals(50, KitchenVolume.sonos(capture = true, kitchenVolume = -1, mirrorOn = true, mirrorRaw = 32_768, connectRaw = -1, balance = 0))
        assertEquals(55, KitchenVolume.sonos(true, -1, true, 32_768, -1, 5))
        // before the first report there is nothing to follow
        assertEquals(-1, KitchenVolume.sonos(true, -1, true, -1, -1, 5))
    }

    @Test
    fun spotifysOwnPlaybackFollowsThePhoneAsBefore() {
        assertEquals(50, KitchenVolume.sonos(capture = false, kitchenVolume = 20, mirrorOn = true, mirrorRaw = 10_000, connectRaw = 32_768, balance = 0))
        assertEquals(54, KitchenVolume.sonos(false, -1, false, -1, 32_768, 4))
        assertEquals(-1, KitchenVolume.sonos(false, 20, false, -1, -1, 0))
    }
}
