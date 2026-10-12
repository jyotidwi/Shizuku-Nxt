package moe.shizuku.manager.start

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether an instruction is still worth showing.
 *
 * Each of these kinds is one thing to do on the phone - join a network, switch wireless debugging
 * on, open the port - and the question is whether it has since been done. Answering yes when it has
 * is what leaves somebody looking at a step they have already taken.
 */
class StartStatusTest {

    private val connected = true
    private val wirelessOn = true
    private val port = 5555

    @Test
    fun `a failure about the network is over once there is a network`() {
        assertTrue(StartFailureKind.WIFI.stillInTheWay(wifiConnected = false, wirelessDebuggingOn = wirelessOn, adbPort = port))
        assertFalse(StartFailureKind.WIFI.stillInTheWay(wifiConnected = connected, wirelessDebuggingOn = wirelessOn, adbPort = port))
    }

    @Test
    fun `a failure about wireless debugging is over once it is on`() {
        assertTrue(StartFailureKind.SETTINGS.stillInTheWay(wifiConnected = connected, wirelessDebuggingOn = false, adbPort = port))
        assertFalse(StartFailureKind.SETTINGS.stillInTheWay(wifiConnected = connected, wirelessDebuggingOn = wirelessOn, adbPort = port))
    }

    @Test
    fun `a failure about the port is over once there is a port`() {
        assertTrue(StartFailureKind.PORT.stillInTheWay(wifiConnected = connected, wirelessDebuggingOn = wirelessOn, adbPort = -1))
        assertTrue(StartFailureKind.PORT.stillInTheWay(wifiConnected = connected, wirelessDebuggingOn = wirelessOn, adbPort = 0))
        assertFalse(StartFailureKind.PORT.stillInTheWay(wifiConnected = connected, wirelessDebuggingOn = wirelessOn, adbPort = port))
    }

    @Test
    fun `pairing stands until a start answers for it`() {
        // Nothing but a real connection knows whether a phone is paired, and that connection is the
        // start itself: so this one is kept rather than guessed away.
        assertTrue(StartFailureKind.PAIRING.stillInTheWay(connected, wirelessOn, port))
    }

    @Test
    fun `a failure with no particular cause is kept`() {
        assertTrue(StartFailureKind.GENERIC.stillInTheWay(connected, wirelessOn, port))
    }
}
