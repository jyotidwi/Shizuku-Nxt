package moe.shizuku.manager.start

/**
 * Which transport a start will use, as a decision that can be reasoned about without a
 * device.
 *
 * Two of them carry a start: the wireless (TLS) port, which is found over mDNS and so
 * needs a network interface, and the classic ADB port, which is a number that is already
 * known and needs nothing at all. TCP mode is what keeps the classic port open, so once
 * it is on and the port is there a start does not need Wi-Fi which is the point of the
 * mode, and what makes a restart after a reboot work on a device with no network.
 *
 * The two questions here have to be answered together, and for a while they were not: a
 * start that was told it needed no network still went looking for a wireless port over
 * mDNS - which needs exactly the network that was just declared unnecessary - and reached
 * the port it already had only after that search timed out, two minutes later for the
 * forced-wireless method. On a phone whose wireless debugging TCP mode itself had switched
 * away, the search could not succeed at all.
 */
object StartTransport {

    /**
     * Whether a start has to wait for a network before it can reach the TLS port.
     *
     * [forceWireless] is the experimental setting that asks for wireless debugging
     * without a network at all: waiting for one would gate the exact start it exists to
     * make possible, which is why it overrides everything else here.
     */
    fun wifiRequired(tcpPort: Int, tcpMode: Boolean, forceWireless: Boolean = false): Boolean =
        !forceWireless && (tcpPort <= 0 || !tcpMode)

    /**
     * Whether a start should go over the classic ADB port rather than look for a wireless one.
     *
     * The USB method is that port, and a platform without wireless debugging has no other; what is
     * added here is the port TCP mode is keeping open, which is the one that mode exists to provide.
     * [wifiRequired] has already promised that a start in this state needs no network, and this is
     * the answer that makes the promise hold.
     *
     * On Android 17 a port is not merely the faster answer, it is the only one: the platform now
     * refuses to run adbd's wireless server without a wireless network at all - "Not connected to
     * any wireless network. Not enabling adbwifi.", measured with a local-only hotspot up and a
     * wireless network absent - so searching for a wireless port there is asking for a port that
     * cannot exist, and the search is what a networkless start spends its whole window on.
     *
     * [portListening] is the answer only the caller can give, because it is about the phone rather
     * than about the request: `service.adb.tcp.port` outlives adbd, so a number in it is not a port
     * that is there. A phone whose debugging toggles were turned off when Shizuku stopped still
     * reads the port the daemon was using, and taking that number sent a wireless start to a socket
     * nothing was listening on - while skipping the mDNS search that is the only thing which turns
     * wireless debugging back on, which is what the start after that stop has to do. Issue #71:
     * a start there failed with the toggles still off, and needed the toggle switched on by hand.
     */
    fun classicPortInUse(
        usbMethod: Boolean,
        tlsSupported: Boolean,
        tcpPort: Int,
        tcpMode: Boolean,
        portListening: Boolean
    ): Boolean = usbMethod || !tlsSupported ||
        (tcpMode && classicPortFallback(tcpPort) != null && portListening)

    /**
     * The port to use when discovery over mDNS has found nothing, or null when there is
     * nothing to fall back to.
     */
    fun classicPortFallback(tcpPort: Int): Int? = tcpPort.takeIf { it > 0 }
}
