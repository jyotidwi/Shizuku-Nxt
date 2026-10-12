package moe.shizuku.manager.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The break opportunities a revealed wifi key is drawn with.
 *
 * The point of them is that a key is one unbroken word and cannot wrap without them, and the risk
 * of them is the other half: a character added to a key that somebody is reading off the screen (or
 * off a photograph of it) the wrong one. Both are checked here, on the one function that decides.
 */
class WifiPasswordsScreenTest {

    private val breakCharacter = '\u200B'

    /** A real key at its longest: WPA2 allows sixty-three characters, with no spaces in them. */
    private val longestKey = "Ab3Xy7".repeat(11).take(63)

    @Test
    fun `what is drawn is the key, with breaks and nothing else`() {
        val drawn = breakable(longestKey)

        assertEquals(longestKey, drawn.replace(breakCharacter.toString(), ""))
    }

    @Test
    fun `a long key gets somewhere to wrap`() {
        val drawn = breakable(longestKey)

        assertTrue("a 63 character key needs breaks to fit a row", drawn.count { it == breakCharacter } > 1)
        // Nowhere near every character: four at a time is what leaves a key legible when it wraps.
        assertTrue(drawn.count { it == breakCharacter } <= longestKey.length / 4 + 1)
    }

    @Test
    fun `a short key is left alone`() {
        // Shorter than one chunk, so there is nothing to break between and no character added.
        assertEquals("abc", breakable("abc"))
        assertFalse(breakable("abc").contains(breakCharacter))
    }

    @Test
    fun `a key that is exactly one chunk long is still left alone`() {
        assertEquals("abcd", breakable("abcd"))
    }

    @Test
    fun `an empty key stays empty`() {
        assertEquals("", breakable(""))
    }

    @Test
    fun `the break is the character that exists to be one`() {
        // U+200B, the zero width space: it draws as nothing and its whole purpose is to be a place a
        // line may end, which is what a run of characters with no spaces in it needs.
        val breaks = breakable("abcdefgh").filter { it == breakCharacter }

        assertTrue(breaks.isNotEmpty())
        assertEquals(0x200B, breaks.first().code)
    }
}
