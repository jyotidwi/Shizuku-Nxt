package moe.shizuku.manager.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What `cmd appops query-op` printed, read as package names.
 *
 * Issue #51 is why this exists. A screen said "1 blocked" over an empty list, and a tile grew a
 * dot for a feature that had never been used: `query-op` had answered with a *message* rather
 * than a name - a platform with no such op prints one - and every non-empty line was taken for
 * a package, which is a name that no list can show.
 */
class OpDeniedParseTest {

    @Test
    fun `package names come back as themselves`() {
        assertEquals(
            setOf("com.example.one", "com.example.two"),
            PackageTools.parseOpDenied("com.example.one\ncom.example.two\n")
        )
    }

    @Test
    fun `a message is not a package`() {
        assertTrue(
            PackageTools.parseOpDenied("Error: Unknown operation string: RUN_ANY_IN_BACKGROUND")
                .isEmpty()
        )
    }

    @Test
    fun `a line that is only part of a sentence is not a package`() {
        assertTrue(PackageTools.parseOpDenied("No operations.").isEmpty())
    }

    @Test
    fun `surrounding whitespace is trimmed and blank lines dropped`() {
        assertEquals(
            setOf("com.example.one"),
            PackageTools.parseOpDenied("\n  com.example.one  \n\n")
        )
    }

    @Test
    fun `an interior tab is not a package name`() {
        assertTrue(PackageTools.parseOpDenied("com.example\tone").isEmpty())
    }

    @Test
    fun `nothing printed is nothing denied`() {
        assertTrue(PackageTools.parseOpDenied(null).isEmpty())
        assertTrue(PackageTools.parseOpDenied("").isEmpty())
    }
}
