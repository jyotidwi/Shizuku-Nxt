package moe.shizuku.manager.manage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The arithmetic behind the wifi control layer: what `cmd wifi` printed, turned into rows and a
 * name.
 *
 * Every string below is the output a device really produced - `list-networks` and `status` from an
 * S938U1 on Android 16, and the `WifiInfo` form from the same phone's `dumpsys wifi`, which is the
 * shape `status` prints when something is joined. They are here rather than invented because the
 * columns are padded rather than separated and an SSID has spaces in it, which is the whole reason
 * the parsing has rules at all.
 */
class WifiControlTest {

    private val listNetworks = """
        Network Id      SSID                         Security type
        0            221 - IoT                        wpa2-psk
        0            221 - IoT                        wpa3-sae^
        1            221                              wpa3-sae
        2            3D-Zc-ycxRt                      open
        2            3D-Zc-ycxRt                      owe^
        3            ABFSS_Conc                       wpa2-psk
    """.trimIndent()

    @Test
    fun `a list is one row per id, not one per printed line`() {
        val entries = WifiControl.entries(listNetworks)

        // Six lines printed, four networks: the two transition rows are not networks of their own,
        // and the ids are the printed ones rather than positions in the list.
        assertEquals(4, entries.size)
        assertEquals(setOf(0, 1, 2, 3), entries.map { it.id }.toSet())
        // In the order the names come in, which is what the screen draws: 221 before 221 - IoT,
        // then 3D-Zc-ycxRt, then ABFSS_Conc.
        assertEquals(listOf(1, 0, 2, 3), entries.map { it.id })
    }

    @Test
    fun `the second security type of a transition network is not a second network`() {
        val zero = WifiControl.entries(listNetworks).first { it.id == 0 }

        assertEquals("221 - IoT", zero.ssid)
        assertEquals("wpa2-psk", zero.security)
    }

    @Test
    fun `a name with spaces in it stays one name`() {
        assertEquals("221 - IoT", WifiControl.entries(listNetworks).first { it.id == 0 }.ssid)
    }

    @Test
    fun `rows are ordered by name, the way the screen shows them`() {
        val names = WifiControl.entries(listNetworks).map { it.ssid }

        assertEquals(listOf("221", "221 - IoT", "3D-Zc-ycxRt", "ABFSS_Conc"), names)
    }

    @Test
    fun `a header and blank lines are not networks`() {
        assertEquals(
            0,
            WifiControl.entries("Network Id      SSID                         Security type\n\n").size
        )
    }

    @Test
    fun `a phone with the radio off is told so, whatever the command answered`() {
        // `cmd wifi connect-network` exits 0 on a phone with wifi off and joins nothing, which is
        // why the radio is asked about on its own rather than inferred from the command.
        assertEquals(WifiControl.Join.RADIO_OFF, WifiControl.joinVerdict(false, 0, null, "Home"))
        assertEquals(WifiControl.Join.RADIO_OFF, WifiControl.joinVerdict(false, 0, "Other", "Home"))
    }

    @Test
    fun `joining is the name the status reports, not the exit code`() {
        assertEquals(WifiControl.Join.CONNECTED, WifiControl.joinVerdict(true, 0, "Home", "Home"))
        // The same name in another case is the same network.
        assertEquals(WifiControl.Join.CONNECTED, WifiControl.joinVerdict(true, 0, "HOME", "home"))
        // Being joined to something else is not having joined this one.
        assertEquals(WifiControl.Join.FAILED, WifiControl.joinVerdict(true, 0, "Other", "Home"))
        assertEquals(WifiControl.Join.FAILED, WifiControl.joinVerdict(true, 0, null, "Home"))
        assertEquals(WifiControl.Join.FAILED, WifiControl.joinVerdict(true, 1, "Home", "Home"))
    }

    @Test
    fun `an unreadable radio does not stop the attempt`() {
        // Null is "the phone did not say", which is not the same as off: the attempt goes ahead
        // and the command's own answer decides.
        assertEquals(WifiControl.Join.CONNECTED, WifiControl.joinVerdict(null, 0, "Home", "Home"))
        assertEquals(WifiControl.Join.FAILED, WifiControl.joinVerdict(null, 0, null, "Home"))
    }

    @Test
    fun `a radio that is off says so, and has nothing joined`() {
        val state = statusOf("Wifi is disabled\nWifi scanning is only available when wifi is enabled")

        assertEquals(false, state)
    }

    @Test
    fun `the name a phone is joined to comes out of the status line`() {
        val printed = "Wifi is enabled\n" +
            "WifiInfo: SSID: \"221 - IoT\", BSSID: <none>, MAC: 02:00:00:00:00:00, " +
            "Supplicant state: COMPLETED, RSSI: -50"

        assertEquals("221 - IoT", WifiControl.connected(printed))
    }

    @Test
    fun `an enabled radio with nothing joined has no name`() {
        val printed = "Wifi is enabled\n" +
            "WifiInfo: SSID: <unknown ssid>, BSSID: <none>, MAC: 02:00:00:00:00:00, " +
            "Supplicant state: DISCONNECTED, RSSI: -127"

        assertNull(WifiControl.connected(printed))
    }

    @Test
    fun `nothing at all is not a name either`() {
        assertNull(WifiControl.connected("Wifi is enabled"))
    }

    @Test
    fun `the security labels this app shows are what connect-network wants`() {
        assertEquals("wpa2", WifiControl.type("WPA2"))
        assertEquals("wpa3", WifiControl.type("WPA3"))
        assertEquals("wep", WifiControl.type("WEP"))
        assertEquals("owe", WifiControl.type("OWE"))
        assertEquals("open", WifiControl.type("Open"))
    }

    @Test
    fun `an ssid or a key with a quote in it survives the shell`() {
        assertEquals("'plain'", WifiControl.quote("plain"))
        assertEquals("'two words'", WifiControl.quote("two words"))
        assertEquals("'it'\\''s'", WifiControl.quote("it's"))
    }

    /** The name `state()` would read, without a shell to run it through. */
    private fun statusOf(printed: String): Boolean =
        WifiControl.stateOf(printed)?.enabled == true

    @Test
    fun `a status that says neither thing is not a phone with wifi on`() {
        // What a read taken while the radio is being switched actually looked like on the device
        // that found this: one line, and not either of the two the command prints.
        assertNull(WifiControl.stateOf("Wifi is unavailable"))
        assertNull(WifiControl.stateOf(""))

        assertEquals(false, WifiControl.stateOf("Wifi is disabled")?.enabled)
        assertEquals(true, WifiControl.stateOf("Wifi is enabled")?.enabled)
    }

    @Test
    fun `an empty listing is not believed while there are networks`() {
        val previous = mapOf("221 - iot" to 0)

        // The moment after a forget: the store is being rewritten and the listing has no rows.
        assertEquals(previous, WifiControl.idsBySsid(emptyList(), networks = 5, previous = previous))
    }

    @Test
    fun `an empty listing is the truth when there is nothing to list`() {
        val previous = mapOf("221 - iot" to 0)

        assertEquals(emptyMap<String, Int>(), WifiControl.idsBySsid(emptyList(), networks = 0, previous = previous))
    }

    @Test
    fun `names are matched whatever their case is`() {
        val saved = listOf(WifiControl.Entry(id = 7, ssid = "Home_5G", security = "wpa2-psk"))

        val ids = WifiControl.idsBySsid(saved, networks = 1, previous = emptyMap())

        assertEquals(7, ids["home_5g"])
    }
}
