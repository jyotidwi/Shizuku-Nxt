package moe.shizuku.manager.manage

import android.content.AttributionSource
import android.net.wifi.WifiConfiguration
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import moe.shizuku.manager.utils.Diag
import moe.shizuku.manager.utils.ShizukuStateMachine
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * The passwords of the saved wifi networks.
 *
 * Everywhere in Settings a saved password is a row of dots, and that is not a redaction the app
 * can undo: the network list an app is allowed to read is the *privileged* one, which only the
 * system and the shell may ask for - and the key is what coming through that door buys. This app
 * has the shell, so the same list is `getPrivilegedConfiguredNetworks` asked for as uid 2000, and
 * the keys come back as themselves rather than as the `"*"` a public read leaves in their place.
 *
 * The service behind it is not in the SDK, so nothing here is a declared API: the interface class
 * is looked up by name, the method by name and arguments, and [HiddenApiBypass] is what makes both
 * reachable - the same tool the activities launcher in this module reaches `SearchManager` with.
 * That is also why the call is written as a list of shapes to try rather than one signature: the
 * extra `Bundle` arrived with Android 13 (it carries the attribution source) and the two-argument
 * form is what came before it, so the service is asked which it declares instead of the SDK level
 * being asked what it should have.
 *
 * Read only, deliberately. The service can also forget a network, add one and connect to it, but
 * every one of those changes the phone's saved state, and a screen whose whole point is showing
 * somebody a password they have forgotten has no business writing anything back.
 */
object WifiPasswords {

    private const val TAG = "WifiPasswords"

    /**
     * The package the call is attributed to, which has to be the shell's.
     *
     * Both the `packageName` argument and the attribution tag are it: the permission being borrowed
     * is the shell's, and the service checks the name against the uid it was told to act as.
     */
    private const val SHELL_PACKAGE = "com.android.shell"

    /** The key the service reads the attribution source out of, spelled as AOSP spells it. */
    private const val ATTRIBUTION_KEY = "EXTRA_PARAM_KEY_ATTRIBUTION_SOURCE"

    private const val IWIFI_MANAGER_STUB = "android.net.wifi.IWifiManager\$Stub"

    /** The privileged list, the one call in this class that is the point of it. */
    private const val PRIVILEGED_NETWORKS = "getPrivilegedConfiguredNetworks"

    /** One saved network, as much of it as a person came for. */
    data class SavedNetwork(
        val ssid: String,
        val password: String,
        /** For the label beside the key: some networks have none to show. */
        val security: String
    )

    /** What a read produced, so the screen can say which of the three states it is in. */
    sealed interface Outcome {
        data class Networks(val items: List<SavedNetwork>) : Outcome

        /** Nothing to ask: there is no server, so there is no shell to borrow. */
        object NoServer : Outcome

        /** The server was there and the call did not answer. */
        object Failed : Outcome

        /** Android 8 or older, where the hidden api cannot be reached at all. */
        object Unsupported : Outcome
    }

    fun read(): Outcome {
        if (!ShizukuStateMachine.isRunning()) return Outcome.NoServer

        // HiddenApiBypass needs Android 9; below it there is no way to the hidden wifi service,
        // so the answer is that this cannot be done on this phone rather than a failure.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return Outcome.Unsupported

        return try {
            Outcome.Networks(networks())
        } catch (t: Throwable) {
            // Written down before it is swallowed: this is the one place a device that refuses the
            // call gets to say why, and "nothing came back" is otherwise indistinguishable from a
            // phone with no saved networks at all.
            Diag.warn(TAG, "reading the saved wifi networks failed", t)
            Outcome.Failed
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun networks(): List<SavedNetwork> {
        val service = service()
        val extras = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Bundle().apply { putParcelable(ATTRIBUTION_KEY, attributionSource()) }
        } else {
            null
        }

        return configuredNetworks(service, extras)
            .mapNotNull(::network)
            // One row per name. The store keeps more than one config for the same SSID - an
            // ephemeral copy beside the saved one, or a network that was added twice - and two
            // rows headed the same are noise on a screen that exists to show a key. The copy
            // that has a key is the one kept, so a blank duplicate cannot hide a real password.
            .groupBy { it.ssid }
            .map { (_, copies) -> copies.firstOrNull { it.password.isNotEmpty() } ?: copies.first() }
            .sortedBy { it.ssid.lowercase() }
    }

    /**
     * The wifi service, as the shell would see it.
     *
     * [ShizukuBinderWrapper] is the whole of it: it hands Shizuku the binder and lets it make the
     * call, so the uid the service checks is 2000 and the permission it wants is one the shell
     * holds. Without the wrapper this is an ordinary app asking, and the answer is a refusal.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun service(): Any {
        val binder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("wifi"))
        val stub = Class.forName(IWIFI_MANAGER_STUB)
        return requireNotNull(HiddenApiBypass.invoke(stub, null, "asInterface", binder)) {
            "the wifi service has no binder"
        }
    }

    /**
     * The attribution source the Android 13+ shape of the call carries.
     *
     * The builder rather than a constructor: the platform's own constructor is one of the things
     * this class exists to avoid touching, and the public builder produces the same object.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    private fun attributionSource() = AttributionSource.Builder(Shizuku.getUid())
        .setPackageName(SHELL_PACKAGE)
        .setAttributionTag(SHELL_PACKAGE)
        .build()

    /**
     * `getPrivilegedConfiguredNetworks`, in whichever shape this device declares it.
     *
     * The bundle first, because that is the shape every release since Android 13 has; the two
     * argument form is the fallback for the four before it. A shape that is not declared throws
     * from the lookup, which is the signal to try the next one rather than an error to report.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun configuredNetworks(service: Any, extras: Bundle?): List<WifiConfiguration> {
        val shapes: List<Array<Class<*>>> = listOf(
            arrayOf(String::class.java, String::class.java, Bundle::class.java),
            arrayOf(String::class.java, String::class.java)
        )

        for (parameters in shapes) {
            // Which shape this device declares is asked first, and separately from the call. The
            // call is made by name and cannot say which of the two it wants, so a name that is not
            // there has to be told apart from a call that was refused - the first is what the
            // other shape is for, and the second is the answer.
            val declared = runCatching {
                HiddenApiBypass.getDeclaredMethod(
                    service.javaClass,
                    PRIVILEGED_NETWORKS,
                    *parameters
                )
            }.isSuccess
            if (!declared) continue

            // The bundle is never null on the path that wants it: the three argument shape is the
            // one Android 13 and later declare, and those are the releases that fill the extras.
            val arguments: Array<Any?> = if (parameters.size == 3) {
                arrayOf(user(), SHELL_PACKAGE, extras ?: Bundle())
            } else {
                arrayOf(user(), SHELL_PACKAGE)
            }

            val slice = HiddenApiBypass.invoke(
                service.javaClass,
                service,
                PRIVILEGED_NETWORKS,
                *arguments
            ) ?: return emptyList()

            return sliceList(slice)
        }

        return emptyList()
    }

    /**
     * The list inside the slice the AIDL answers with.
     *
     * `getList` is declared on the slice's *base* class rather than on the slice itself, and the
     * base class is a hidden one too - so it is not named here either, but looked for up the
     * chain until the class that declares it is found.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun sliceList(slice: Any): List<WifiConfiguration> {
        var type: Class<*>? = slice.javaClass
        while (type != null) {
            if (runCatching { HiddenApiBypass.getDeclaredMethod(type, "getList") }.isSuccess) {
                @Suppress("UNCHECKED_CAST")
                return (HiddenApiBypass.invoke(type, slice, "getList") as? List<WifiConfiguration>)
                    .orEmpty()
            }
            type = type.superclass
        }
        return emptyList()
    }

    /** What the call is made as: the name Shizuku's own uid answers to. */
    private fun user(): String = when (Shizuku.getUid()) {
        0 -> "root"
        1000 -> "system"
        else -> "shell"
    }

    private fun network(config: WifiConfiguration): SavedNetwork? {
        // A config with no SSID is not a network somebody can be shown; there is one of these for
        // every network the platform has forgotten the name of.
        val ssid = config.SSID?.removeSurrounding("\"")?.takeIf { it.isNotEmpty() } ?: return null

        return SavedNetwork(
            ssid = ssid,
            password = password(config),
            security = security(config)
        )
    }

    /**
     * The key, as the config keeps it.
     *
     * A PSK is one value and WEP is four, of which at most one is set. Both are stored wrapped in
     * the quotes they were typed with, which are the field's own syntax rather than part of the
     * key - a password copied with them in it does not paste into anything.
     */
    private fun password(config: WifiConfiguration): String {
        val psk = config.preSharedKey?.removeSurrounding("\"")
        if (!psk.isNullOrEmpty()) return psk

        return config.wepKeys.orEmpty()
            .firstOrNull { !it.isNullOrBlank() }
            ?.removeSurrounding("\"")
            .orEmpty()
    }

    /**
     * How the network is secured, from the key management it allows.
     *
     * WEP is decided by its four keys rather than by the key management, because the bit WEP sets
     * is the same one an open network sets - the difference is the keys, and that is what is asked.
     */
    private fun security(config: WifiConfiguration): String {
        val allowed = config.allowedKeyManagement ?: return "Open"

        return when {
            allowed[WifiConfiguration.KeyMgmt.SAE] -> "WPA3"
            allowed[WifiConfiguration.KeyMgmt.OWE] -> "OWE"
            allowed[WifiConfiguration.KeyMgmt.WPA_PSK] ||
                allowed[WifiConfiguration.KeyMgmt.WPA_EAP] ||
                allowed[WifiConfiguration.KeyMgmt.WPA2_PSK] -> "WPA2"

            config.wepKeys?.any { !it.isNullOrBlank() } == true -> "WEP"
            else -> "Open"
        }
    }
}
