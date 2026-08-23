package com.screensweep.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DriveFileIdentityTest {

    @Test
    fun sameSourceAndUriProducesStableKey() {
        val first = DriveFileIdentity.keyFor("screenshots", "content://media/images/42")
        val second = DriveFileIdentity.keyFor("screenshots", "content://media/images/42")

        assertEquals(first, second)
        assertEquals(64, first.length)
    }

    @Test
    fun sameFilenameLocationInDifferentSourcesCannotCollide() {
        val screenshot = DriveFileIdentity.keyFor("screenshots", "content://provider/image.png")
        val customFolder = DriveFileIdentity.keyFor("tree:folder", "content://provider/image.png")

        assertNotEquals(screenshot, customFolder)
    }

    @Test
    fun differentUrisCannotCollide() {
        val first = DriveFileIdentity.keyFor("screenshots", "content://media/images/1")
        val second = DriveFileIdentity.keyFor("screenshots", "content://media/images/2")

        assertNotEquals(first, second)
    }
}
