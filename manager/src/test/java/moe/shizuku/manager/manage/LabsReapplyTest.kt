package moe.shizuku.manager.manage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the lists this app owns have to be put back.
 *
 * Uptime is the question, and it is the only one asked: it grows within a boot and resets with a
 * reboot, so a number smaller than the last one written means the platform's side of those lists
 * is gone. Getting this wrong in one direction re-applies them on every start, and in the other
 * leaves blocked apps online with the list still claiming otherwise.
 */
class LabsReapplyTest {

    @Test
    fun `a smaller uptime is a reboot`() {
        assertTrue(LabsReapply.isReboot(lastUptime = 900_000L, uptime = 12_000L))
    }

    @Test
    fun `a larger uptime is the same boot`() {
        // A start an hour into the same boot: nothing was taken away, so nothing is put back.
        assertFalse(LabsReapply.isReboot(lastUptime = 12_000L, uptime = 3_612_000L))
    }

    @Test
    fun `a device that has never written one is treated as a reboot`() {
        // Nothing has been applied before, so whatever the list holds is not on the platform.
        assertTrue(LabsReapply.isReboot(lastUptime = -1L, uptime = 5L))
    }

    @Test
    fun `the same uptime is not a reboot`() {
        // Two starts landing in the same millisecond: still once per boot.
        assertFalse(LabsReapply.isReboot(lastUptime = 42L, uptime = 42L))
    }
}
