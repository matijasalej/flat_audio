package io.github.matijasalej.wholeflat.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosWsEventsTest {
    private fun obj(s: String) = JSONObject(s)

    @Test
    fun aPlainStatusHasNoError() {
        val body = obj("""{"playbackState":"PLAYBACK_STATE_PLAYING","positionMillis":1234,"playModes":{"repeat":false}}""")
        assertEquals("", SonosWsEvents.errorText(body))
    }

    @Test
    fun errorFieldsAreFoundByNameWithTheReasonBesideThem() {
        val body = obj("""{"playbackState":"PLAYBACK_STATE_IDLE","errorCode":"ERROR_PLAYBACK_FAILED","reason":"stream unreadable","itemId":"abc"}""")
        assertEquals("errorCode=ERROR_PLAYBACK_FAILED reason=stream unreadable", SonosWsEvents.errorText(body))
    }

    @Test
    fun aReasonWithoutAnErrorIsNotAnError() {
        assertEquals("", SonosWsEvents.errorText(obj("""{"reason":"user request","playbackState":"PLAYBACK_STATE_PAUSED"}""")))
    }

    @Test
    fun nestedErrorFieldsAreFoundToo() {
        val body = obj("""{"playbackState":"PLAYBACK_STATE_IDLE","item":{"track":{"playbackError":{"code":42,"text":"no decoder"}}}}""")
        val text = SonosWsEvents.errorText(body)
        assertTrue(text, text.startsWith("playbackError="))
        assertTrue(text, text.contains("no decoder"))
    }

    @Test
    fun nullAndEmptyErrorFieldsAreSkipped() {
        assertEquals("", SonosWsEvents.errorText(obj("""{"errorCode":null,"playbackError":""}""")))
    }

    @Test
    fun aPlaybackErrorEventCarriesItsError() {
        val header = obj("""{"namespace":"playback:1","type":"playbackError","groupId":"g1","success":true}""")
        val body = obj("""{"errorCode":"ERROR_PLAYBACK_NO_CONTENT","reason":"the stream ended at once"}""")
        val e = SonosWsEvents.parse(header, body)
        assertNotNull(e)
        assertEquals("playbackError", e!!.type)
        assertEquals("errorCode=ERROR_PLAYBACK_NO_CONTENT reason=the stream ended at once", e.error)
    }

    @Test
    fun aPlaybackStatusEventCarriesTheStateAndAnyError() {
        val header = obj("""{"namespace":"playback:1","type":"playbackStatus"}""")
        val e = SonosWsEvents.parse(header, obj("""{"playbackState":"PLAYBACK_STATE_IDLE","isDucking":false}"""))
        assertEquals("playbackStatus", e!!.type)
        assertEquals("PLAYBACK_STATE_IDLE", e.state)
        assertEquals("", e.error)
    }

    @Test
    fun otherMessagesAreNotEvents() {
        assertNull(SonosWsEvents.parse(obj("""{"namespace":"groups:1","type":"groups"}"""), obj("{}")))
        assertNull(SonosWsEvents.parse(obj("""{"namespace":"playback:1"}"""), obj("{}")))
    }
}
