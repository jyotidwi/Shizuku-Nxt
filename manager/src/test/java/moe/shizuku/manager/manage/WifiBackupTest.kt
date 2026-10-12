package moe.shizuku.manager.manage

import android.os.Build
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The export file: the text, the two wrappings, and the parts of the format that are a contract
 * with another app.
 *
 * The encryption is exercised for real rather than mocked - it is `javax.crypto` and the test runs
 * on a JVM, where the same `PBKDF2WithHmacSHA256` the phone has from Android 8 exists. The one
 * thing a test here cannot see is the version guard around it, which is that API's own 26.
 */
class WifiBackupTest {

    private val networks = listOf(
        WifiBackup.Network(ssid = "221 - IoT", password = "hunter2", security = "WPA2"),
        WifiBackup.Network(ssid = "Cafe", password = "", security = "Open")
    )

    // -- the text ------------------------------------------------------------------------------

    @Test
    fun `what we write is the shape the other app reads`() {
        val text = WifiBackup.text(networks)

        // A bare array, `security` as a list, and the key under the name they use for it.
        assertTrue(text.startsWith("["))
        assertTrue(text.contains("\"ssid\":\"221 - IoT\""))
        assertTrue(text.contains("\"security\":[\"WPA2\"]"))
        assertTrue(text.contains("\"password\":\"hunter2\""))
    }

    @Test
    fun `a file written by the other app reads back whole`() {
        // Their writer, including the fields this screen has no use for.
        val theirs = """
            [{"ssid":"Home_5G","security":["WPA2","WPA3"],"password":"secret",
              "hidden":true,"autojoin":false,"private":false,"note":"the one by the kitchen"}]
        """.trimIndent()

        val parsed = WifiBackup.parse(theirs)

        assertEquals(1, parsed.size)
        assertEquals("Home_5G", parsed[0].ssid)
        assertEquals("secret", parsed[0].password)
        // Two security types become the one label this app shows, which is what adding it back
        // needs; the rest of the metadata is kept only where the platform has a flag for it.
        assertEquals("WPA2", parsed[0].security)
        assertTrue(parsed[0].hidden)
        assertFalse(parsed[0].autojoin)
    }

    @Test
    fun `a network with no name is not a network`() {
        assertEquals(0, WifiBackup.parse("""[{"password":"x","security":["WPA2"]}]""").size)
        assertEquals(0, WifiBackup.parse("[]").size)
    }

    // -- the wrappings -------------------------------------------------------------------------

    @Test
    fun `a plain file is the text, and reads back`() {
        val bytes = WifiBackup.encode(networks, WifiBackup.Format.PLAIN, password = null)!!

        assertEquals(WifiBackup.Kind.TEXT, WifiBackup.kind(bytes))
        assertArrayEquals(networks.toTypedArray(), decoded(bytes).toTypedArray())
    }

    @Test
    fun `a compressed file is gzip and reads back`() {
        val bytes = WifiBackup.encode(networks, WifiBackup.Format.COMPRESSED, password = null)!!

        assertEquals(WifiBackup.Kind.GZIP, WifiBackup.kind(bytes))
        assertArrayEquals(networks.toTypedArray(), decoded(bytes).toTypedArray())
    }

    @Test
    fun `a password wraps either format, and the same password opens it`() {
        WifiBackup.Format.entries.forEach { format ->
            val inner = WifiBackup.text(networks).toByteArray()
            // Through the cipher directly rather than through [WifiBackup.encode]: a JVM has no
            // device version, so the guard that keeps this off Android 7 refuses a password here,
            // and that refusal is the test below rather than this one.
            val container = WifiBackup.encrypt(inner, "correct horse", format)

            assertEquals(format.name, WifiBackup.Kind.ENCRYPTED, WifiBackup.kind(container))
            assertArrayEquals(format.name, inner, WifiBackup.decrypt(container, "correct horse"))
        }
    }

    @Test
    fun `the container says nothing about what is inside it`() {
        val inner = WifiBackup.text(networks).toByteArray()
        val one = WifiBackup.encrypt(inner, "pw", WifiBackup.Format.COMPRESSED)
        val other = WifiBackup.encrypt(inner, "pw", WifiBackup.Format.COMPRESSED)

        // A fresh salt and vector every time, or two exports of one phone would be the same file.
        assertNotEquals(one.toList(), other.toList())
        // And the keys are not sitting in it in the clear.
        assertFalse(String(one, Charsets.ISO_8859_1).contains("hunter2"))
    }

    @Test
    fun `an encrypted file with no password asks for one rather than failing`() {
        val container = WifiBackup.encrypt(
            WifiBackup.text(networks).toByteArray(),
            "pw",
            WifiBackup.Format.PLAIN
        )

        assertEquals(WifiBackup.Decoded.NeedsPassword, WifiBackup.decode(container, password = null))
        assertEquals(WifiBackup.Decoded.NeedsPassword, WifiBackup.decode(container, ""))
    }

    @Test
    fun `the wrong password is a failure, not an empty list`() {
        val container = WifiBackup.encrypt(
            WifiBackup.text(networks).toByteArray(),
            "pw",
            WifiBackup.Format.PLAIN
        )

        // The tag decides it, not a comparison of text: a wrong password comes back as nothing at
        // all rather than as noise for a parser to choke on.
        assertNull(WifiBackup.decrypt(container, "not the password"))
    }

    @Test
    fun `something that is not one of these files is a failure`() {
        assertEquals(WifiBackup.Decoded.Failed, WifiBackup.decode("hello".toByteArray(), null))
        assertEquals(WifiBackup.Decoded.Failed, WifiBackup.decode(byteArrayOf(), null))
    }

    // -- names ---------------------------------------------------------------------------------

    @Test
    fun `the name says how the file was written`() {
        assertEquals(
            "shizuku-wifi-20260101-1200.json",
            WifiBackup.fileName(WifiBackup.Format.PLAIN, encrypted = false, stamp = "20260101-1200")
        )
        assertEquals(
            "shizuku-wifi-20260101-1200.json.gz",
            WifiBackup.fileName(WifiBackup.Format.COMPRESSED, encrypted = false, stamp = "20260101-1200")
        )
        assertEquals(
            "shizuku-wifi-20260101-1200.json.gz.bin",
            WifiBackup.fileName(WifiBackup.Format.COMPRESSED, encrypted = true, stamp = "20260101-1200")
        )
    }

    @Test
    fun `a security type is read from either shape, and written in theirs`() {
        assertEquals("WPA2", WifiBackup.securityLabel("WPA2"))
        assertEquals("WPA2", WifiBackup.securityLabel(org.json.JSONArray().put("wpa2")))
        // Nothing said is an open network, and it reads back as the label this app shows for one.
        assertEquals("Open", WifiBackup.securityLabel(null))
        assertEquals("Open", WifiBackup.securityLabel(org.json.JSONArray()))

        assertEquals("WPA3", WifiBackup.securityName("WPA3"))
        assertEquals("OPEN", WifiBackup.securityName("Open"))
    }

    @Test
    fun `a password is refused where the key stretching does not exist`() {
        // A JVM has no device version, so the guard reads as "too old" - the branch that must not
        // call the Android 8 API. A password is then refused rather than written into a file this
        // phone could not read back.
        assertEquals(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O, WifiBackup.canEncrypt())
        assertNull(WifiBackup.encode(networks, WifiBackup.Format.PLAIN, password = "pw"))
    }

    private fun decoded(bytes: ByteArray, password: String? = null): List<WifiBackup.Network> =
        when (val result = WifiBackup.decode(bytes, password)) {
            is WifiBackup.Decoded.Networks -> result.networks
            else -> {
                assertNull("expected networks, got $result", null)
                emptyList()
            }
        }
}
