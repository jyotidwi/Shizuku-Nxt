package moe.shizuku.manager.start

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a stored value of the process-monitor setting means.
 *
 * Worth a test because the sense is inverted and the default is the opposite of what the row
 * shows: the permissions page carries a tick when the monitor is *off*, and a device where the
 * setting has never been written - a null - has the monitor running. Read backwards, that page
 * would claim a change that was never made.
 */
class PhantomProcessKillerTest {

    @Test
    fun `zero and false are the monitor off`() {
        assertTrue(PhantomProcessKiller.isDisabledValue("0"))
        assertTrue(PhantomProcessKiller.isDisabledValue("false"))
        assertTrue(PhantomProcessKiller.isDisabledValue(" FALSE "))
    }

    @Test
    fun `one and true are the monitor running`() {
        assertFalse(PhantomProcessKiller.isDisabledValue("1"))
        assertFalse(PhantomProcessKiller.isDisabledValue("true"))
    }

    @Test
    fun `a setting nobody has written leaves the monitor running`() {
        assertFalse(PhantomProcessKiller.isDisabledValue(null))
        assertFalse(PhantomProcessKiller.isDisabledValue(""))
    }
}
