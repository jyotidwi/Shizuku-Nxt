package moe.shizuku.manager.manage

import moe.shizuku.manager.ShizukuSettings

/**
 * The gate on the two lists that are about an app op: firewall and autostart.
 *
 * The hiding lists have one because their rule lives in this app - a signal is hidden while the app
 * says so, and the list of apps it is hidden for is kept either way. These two have no such rule:
 * what they act on is what the platform reports, so switching them off used to be impossible
 * without emptying the list.
 *
 * So the gate keeps the list itself. Turning it off writes down what was on the list and puts every
 * app on it back, leaving the list in place; turning it on puts the same apps back. Nothing is
 * guessed at in between, and a round trip through off and on returns exactly the apps that were
 * there before, which is what makes it safe to offer.
 *
 * Stored beside the rest of the settings, under a key per list: these are two settings somebody
 * changes on this screen, not state the platform owns.
 */
object BlockedGates {

    private const val ENABLED_PREFIX = "labs_gate_"
    private const val KEPT_PREFIX = "labs_kept_"

    /** Whether the list is being applied. On until somebody says otherwise. */
    fun isEnabled(key: String): Boolean =
        ShizukuSettings.getPreferences().getBoolean(ENABLED_PREFIX + key, true)

    fun setEnabled(key: String, enabled: Boolean) {
        ShizukuSettings.getPreferences().edit()
            .putBoolean(ENABLED_PREFIX + key, enabled)
            .apply()
    }

    /** What was on the list when the gate was last turned off. */
    fun kept(key: String): Set<String> =
        ShizukuSettings.getPreferences()
            .getStringSet(KEPT_PREFIX + key, emptySet())
            .orEmpty()
            // Copied: a set handed back by SharedPreferences is not one to mutate.
            .toSet()

    fun setKept(key: String, packages: Set<String>) {
        ShizukuSettings.getPreferences().edit()
            .putStringSet(KEPT_PREFIX + key, packages.toSet())
            .apply()
    }
}
