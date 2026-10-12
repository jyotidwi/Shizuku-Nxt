package moe.shizuku.manager.ui.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The key an app row is identified by, and the user that row belongs to.
 *
 * The key is not cosmetic. A list built from every user's packages holds the same package twice
 * as soon as a phone has a work profile, a Secure Folder, or an app like Island - which is the
 * crash reported as issue #42, on the Apps tab, naming `com.oasisfeng.island.fdroid`: two rows with
 * one key, and a LazyColumn throws before it draws either.
 *
 * The user is what keeps those two rows from being indistinguishable, since they are two grants:
 * one for the app inside the profile and one for the app outside it.
 */
class AppRowKeyTest {

    /** A uid as the platform builds one: the user id above the application id. */
    private fun uid(userId: Int, appId: Int): Int = userId * 100000 + appId

    @Test
    fun `one package for two users gets two keys`() {
        assertNotEquals(
            appRowKey("com.example.app", uid(0, 10123)),
            appRowKey("com.example.app", uid(10, 10123))
        )
    }

    @Test
    fun `one package for one user gets the same key`() {
        assertEquals(
            appRowKey("com.example.app", uid(0, 10123)),
            appRowKey("com.example.app", uid(0, 10123))
        )
    }

    @Test
    fun `two packages under one user do not collide`() {
        assertNotEquals(
            appRowKey("com.example.one", uid(0, 10123)),
            appRowKey("com.example.two", uid(0, 10123))
        )
    }

    @Test
    fun `a row says nothing about the user when it is the reader's own`() {
        assertNull(otherUserId(uid(0, 10123), myUserId = 0))
    }

    @Test
    fun `a row names the user when the app belongs to another profile`() {
        assertEquals(10, otherUserId(uid(10, 10123), myUserId = 0))
        assertEquals(0, otherUserId(uid(0, 10123), myUserId = 10))
    }
}
