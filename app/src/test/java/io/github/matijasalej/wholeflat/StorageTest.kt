package io.github.matijasalej.wholeflat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageTest {
    private val mb = 1024L * 1024L

    @Test
    fun belowThreeHundredMegabytesIsLow() {
        assertTrue(Storage.Info(299 * mb, 4096 * mb, 0).low)
        assertTrue(Storage.Info(0, 4096 * mb, 0).low)
        assertFalse(Storage.Info(300 * mb, 4096 * mb, 0).low)
        assertFalse(Storage.Info(2000 * mb, 4096 * mb, 0).low)
        assertEquals(300L, Storage.LOW_MB)
    }

    @Test
    fun unknownSpaceIsNotCalledLow() {
        assertFalse(Storage.Info(-1, -1, 0).low)
    }

    @Test
    fun megabytesAreRoundedDown() {
        val info = Storage.Info(1536 * 1024, 4096 * mb, 0)
        assertEquals(1L, info.freeMb)
        assertEquals(4096L, info.totalMb)
    }

    @Test
    fun aDirectoryTreeIsAddedUp() {
        val root = File(System.getProperty("java.io.tmpdir"), "wholeflat-storage-test-" + System.nanoTime())
        try {
            val sub = File(root, "a/b")
            assertTrue(sub.mkdirs())
            File(root, "one.bin").writeBytes(ByteArray(1000))
            File(sub, "two.bin").writeBytes(ByteArray(2500))
            assertEquals(3500L, Storage.sizeOf(root))
            assertEquals(0L, Storage.sizeOf(File(root, "missing")))
            assertEquals(1000L, Storage.sizeOf(File(root, "one.bin")))
        } finally {
            root.deleteRecursively()
        }
    }
}
