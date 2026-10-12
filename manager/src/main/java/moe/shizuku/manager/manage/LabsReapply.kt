package moe.shizuku.manager.manage

import android.os.SystemClock
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuApplication
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.utils.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Putting back whatever a reboot took, once the server that can do it is up.
 *
 * A block is `cmd connectivity` state, which is the platform's and a reboot clears, while the list
 * of what this app blocked is its own and survives - so after a restart every blocked app has its
 * network back and only the list still says otherwise. That is issue #75 from the reporting side:
 * rows that claim to be blocked, an app that is not.
 *
 * Run when a start succeeds rather than at boot, because the command needs the shell and there is
 * none at boot until Shizuku itself is up. Once per boot rather than once per start: a start
 * happens for a dozen reasons and only a reboot takes the state away, so the marker is the device's
 * own uptime - it resets with a reboot, which is exactly the question being asked.
 *
 * Only the lists that are this app's are here. Autostart is an app op, which the platform keeps
 * across a reboot and reports back, so there is nothing to put back; and the hiding lists are
 * applied while a listed app is in front by design, not kept on for the device.
 */
object LabsReapply {

    private const val KEY_LAST_UPTIME = "labs_reapply_uptime"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Whether [uptime] says the device has restarted since [lastUptime].
     *
     * Uptime only grows within a boot, so a smaller number is a reboot; a device that has never
     * been through this reported nothing, which is the same thing to do.
     */
    internal fun isReboot(lastUptime: Long, uptime: Long): Boolean =
        lastUptime < 0 || uptime < lastUptime

    /** Re-applies what a reboot took, and leaves a line in the log either way. */
    fun ifRebooted() {
        val context = ShizukuApplication.appContext
        val uptime = SystemClock.elapsedRealtime()
        val preferences = ShizukuSettings.getPreferences()
        if (!isReboot(preferences.getLong(KEY_LAST_UPTIME, -1L), uptime)) return

        // Written before the work rather than after it: an attempt that dies half way is still an
        // attempt, and repeating it every start until one finishes is the worse failure.
        preferences.edit().putLong(KEY_LAST_UPTIME, uptime).apply()

        scope.launch {
            val restored = runCatching { PackageTools.reapplyNetworkBlocked(context) }
                .onFailure { Diag.warn(AppConstants.TAG, "Could not put the firewall list back", it) }
                .getOrDefault(0)
            Diag.info(AppConstants.TAG, "After the reboot: $restored blocked apps put back")
        }
    }
}
