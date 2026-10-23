package io.github.matijasalej.wholeflat.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DayFloorTest {
    private class MemoryStore : DayFloor.Store {
        val data = HashMap<String, String>()

        override fun read(key: String): String? = data[key]

        override fun write(values: Map<String, String?>) {
            for ((k, v) in values) if (v == null) data.remove(k) else data[k] = v
        }
    }

    @Test
    fun aFloorSavedTodayIsThereAgainToday() {
        val store = MemoryStore()
        var day = 20_000L
        val floor = DayFloor(store) { day }
        assertNull(floor.load())
        floor.save(337.5)
        assertEquals(337.5, floor.load()!!, 0.0)
        assertEquals(20_000L, floor.savedDay())
        // a new object over the same store (the app was restarted) finds it
        assertEquals(337.5, DayFloor(store) { day }.load()!!, 0.0)
    }

    @Test
    fun aFloorOfAnEarlierDayIsDroppedAndGone() {
        val store = MemoryStore()
        var day = 20_000L
        val floor = DayFloor(store) { day }
        floor.save(337.5)
        day = 20_001L
        assertNull(floor.load())
        assertTrue("the stale value is removed", store.data.isEmpty())
        assertNull(floor.savedDay())
    }

    @Test
    fun theFloorCanBeCleared() {
        val store = MemoryStore()
        val floor = DayFloor(store) { 20_000L }
        floor.save(340.0)
        floor.clear()
        assertNull(floor.load())
        assertTrue(store.data.isEmpty())
    }

    @Test
    fun garbageInTheStoreIsNoFloor() {
        val store = MemoryStore()
        store.data[DayFloor.VALUE_KEY] = "high"
        store.data[DayFloor.DAY_KEY] = "20000"
        assertNull(DayFloor(store) { 20_000L }.load())
        store.data[DayFloor.VALUE_KEY] = "NaN"
        assertNull(DayFloor(store) { 20_000L }.load())
        store.data[DayFloor.VALUE_KEY] = "330.0"
        store.data[DayFloor.DAY_KEY] = "yesterday"
        assertNull(DayFloor(store) { 20_000L }.load())
    }

    @Test
    fun theDayChangesAtLocalMidnight() {
        val hour = 3_600_000L
        // 1 Jan 2027 23:30 in a zone that is 2 hours ahead of UTC (21:30 UTC) is still the 1st; 00:30 on the 2nd is the next day
        val utc2130 = 1_798_839_000_000L // 2027-01-01T21:30:00Z
        val same = DayFloor.dayOf(utc2130, 2 * hour)
        val next = DayFloor.dayOf(utc2130 + hour, 2 * hour)
        assertEquals(same + 1, next)
        assertEquals(same, DayFloor.dayOf(utc2130 - hour, 2 * hour))
    }
}
