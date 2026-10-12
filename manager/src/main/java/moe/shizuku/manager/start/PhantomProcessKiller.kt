package moe.shizuku.manager.start

import android.content.Context
import android.provider.Settings
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine
import moe.shizuku.manager.utils.runShellCommand

/**
 * The platform's process killer, and the one setting that turns it off.
 *
 * Android 12 added a monitor for the processes an *app* starts - the shell commands and daemons
 * that outlive the app that spawned them. It kills them, and it kills them with `SIGKILL`, which
 * leaves nothing behind in the process's own log: a server that is started at boot and then dies
 * silently is what this looks like from the outside.
 *
 * The server is exactly that kind of process - the manager spawns the starter, the starter spawns
 * `app_process` - so on a phone that reaps them the server starts, hands the binder over, and is
 * killed seconds later. `settings_enable_monitor_phantom_procs` is the switch that stops it.
 *
 * Deliberately not written by the start path. It is a **global** setting: turning it off stops the
 * platform reaping any app's runaway processes, which is a thing to trade knowingly rather than
 * to have done quietly on somebody's behalf. So it lives on the permissions page, where a person
 * says yes and can say no again.
 */
object PhantomProcessKiller {

    private const val TAG = "PhantomProcessKiller"

    /**
     * The key, spelled out: it is not in `Settings.Global` as a constant, because it is meant for
     * developers rather than for apps.
     */
    internal const val SETTING = "settings_enable_monitor_phantom_procs"

    /** The marker the shell answers with, so a command that prints nothing is still an answer. */
    private const val MARK = "phantom-done"

    /** Whether the monitor is off, as the device currently has it. */
    fun isMonitorDisabled(context: Context): Boolean = isDisabledValue(read(context))

    /**
     * What a stored value means: off is off, and everything else - including a device where
     * nobody has ever written the setting - is the monitor running, which is the platform's own
     * default.
     */
    internal fun isDisabledValue(value: String?): Boolean = when (value?.trim()?.lowercase()) {
        "0", "false" -> true
        else -> false
    }

    /**
     * Turns the monitor off.
     *
     * Written as the shell where there is one: this app may hold `WRITE_SECURE_SETTINGS` or may
     * not, and the shell is the side that always can. The app's own write is the fallback, for a
     * phone with no server running and the permission already granted.
     */
    fun disable(context: Context): Boolean = write(context, "0")

    /**
     * Puts the setting back the way it was found.
     *
     * Deleted rather than set to on: an unwritten setting is the platform's default, and setting
     * it to "on" would leave this app's fingerprint in a global setting for a choice the user
     * has just taken back.
     */
    fun restore(context: Context): Boolean = write(context, null)

    private fun write(context: Context, value: String?): Boolean {
        val command = if (value == null) {
            "settings delete global $SETTING"
        } else {
            "settings put global $SETTING $value"
        }

        if (ShizukuStateMachine.isRunning()) {
            // The marker because `settings` answers with nothing: without it an empty answer
            // would be indistinguishable from a command that never ran.
            val answer = runShellCommand("$command; echo $MARK")
            if (answer?.contains(MARK) == true) return true
            Diag.warn(TAG, "the shell could not write $SETTING, trying this app's own")
        }

        return runCatching {
            Settings.Global.putString(context.contentResolver, SETTING, value)
            true
        }.getOrElse { error ->
            Diag.warn(TAG, "writing $SETTING failed", error)
            false
        }
    }

    /** The setting as the device has it, or null when it has never been written. */
    private fun read(context: Context): String? {
        if (ShizukuStateMachine.isRunning()) {
            val answer = runShellCommand("settings get global $SETTING; echo $MARK")
                ?: return fromThisApp(context)
            val value = answer.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && it != MARK }
            // `settings get` says "null" for a setting nobody has written; that is the absence of
            // a value rather than the word.
            if (value != null) return value.takeIf { it != "null" }
        }
        return fromThisApp(context)
    }

    private fun fromThisApp(context: Context): String? = runCatching {
        Settings.Global.getString(context.contentResolver, SETTING)
    }.getOrNull()
}
