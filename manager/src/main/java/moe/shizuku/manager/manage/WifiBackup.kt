package moe.shizuku.manager.manage

import android.os.Build
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The saved networks as a file, and back again.
 *
 * The shape is the one the app this screen was ported from writes, rather than one of our own: a
 * bare JSON array of `{ssid, security, password}`, where `security` is the list of security types
 * the network is saved with. A file exported by either app can therefore be read by the other, and
 * that is worth more than a format with our name on it.
 *
 * Two wrappings sit on top of that text, both also theirs so the two stay interchangeable: gzip,
 * and a password. The password one is a container rather than a rewritten file - the format byte,
 * a salt, an initialisation vector and the ciphertext - because the thing being encrypted may be
 * either of the others, and because a header that says how to read the rest is what lets a reader
 * tell an encrypted file from a plain one at all.
 *
 * Passwords here are all about the file and never about the phone: nothing is derived from the
 * device, no key is stored anywhere, and losing the password loses the file. That is the honest
 * trade for an export whose whole contents are other people's wifi keys.
 */
object WifiBackup {

    /** One network in a file: everything needed to put it back on a phone. */
    data class Network(
        val ssid: String,
        val password: String,
        val security: String,
        /** Their field, kept on import: a hidden network is added with the platform's flag. */
        val hidden: Boolean = false,
        /** Their field, kept on import: a network that may not be joined on its own. */
        val autojoin: Boolean = true
    )

    /** What a file holds before anything is done to it. */
    enum class Format { PLAIN, COMPRESSED }

    /** How a file is wrapped, told apart by its own first bytes rather than by its name. */
    enum class Kind {
        TEXT,
        GZIP,
        ENCRYPTED,

        /** Not one of the three: an empty file, or something that is not from here at all. */
        UNKNOWN
    }

    /** What came out of a file. */
    sealed interface Decoded {
        data class Networks(val networks: List<Network>) : Decoded

        /** It is encrypted and no password was given: ask, then try again with one. */
        object NeedsPassword : Decoded

        /** The wrong password, or not one of these files at all. */
        object Failed : Decoded
    }

    /** The size of each part of the encrypted container, in bytes. */
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12

    /** What the key is stretched to. Their numbers: a file has to stay readable by both apps. */
    private const val ITERATIONS = 100_000
    private const val KEY_BITS = 256
    private const val TAG_BITS = 128

    /**
     * The smallest thing that can be a container: its header, plus the tag a body cannot be
     * shorter than. Used to tell a container from a file that merely starts with the same byte.
     */
    private const val MIN_CONTAINER_BYTES = 1 + SALT_BYTES + IV_BYTES + TAG_BITS / 8

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val ALGORITHM = "AES"

    private const val SPACE = ' '.code.toByte()
    private const val TAB = '\t'.code.toByte()
    private const val NEWLINE = '\n'.code.toByte()
    private const val RETURN = '\r'.code.toByte()
    private const val OPEN_BRACKET = '['.code.toByte()
    private const val OPEN_BRACE = '{'.code.toByte()

    /**
     * Whether a password can be offered at all.
     *
     * `PBKDF2WithHmacSHA256` arrived with Android 8; below it there is only the SHA-1 form, and a
     * file made with one of those does not open with the other. Rather than write files that only
     * some phones can read, the password is not offered on those phones and the plain export is.
     */
    fun canEncrypt(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O

    /** What a file is, from its first bytes. */
    fun kind(bytes: ByteArray): Kind = when {
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() -> Kind.GZIP
        // Skipping the whitespace a pretty-printed file may open with, so "[ ..." is still text.
        bytes.firstOrNull { it != SPACE && it != TAB && it != NEWLINE && it != RETURN }
            ?.let { it == OPEN_BRACKET || it == OPEN_BRACE } == true -> Kind.TEXT

        // A container is a format number, a salt, a vector and a body. Asking for all of that is
        // what keeps any other file that happens to start with a 0 from being reported as one this
        // app should ask a password for.
        bytes.size >= MIN_CONTAINER_BYTES && bytes[0].toInt() in 0 until Format.entries.size ->
            Kind.ENCRYPTED

        else -> Kind.UNKNOWN
    }

    /**
     * The file to write, or null when a password was asked for on a phone that cannot make one.
     */
    fun encode(networks: List<Network>, format: Format, password: String?): ByteArray? {
        val body = when (format) {
            Format.PLAIN -> text(networks).toByteArray(Charsets.UTF_8)
            Format.COMPRESSED -> gzip(text(networks).toByteArray(Charsets.UTF_8))
        }

        if (password.isNullOrEmpty()) return body
        if (!canEncrypt()) return null

        return encrypt(body, password, format)
    }

    /** What the file holds, or why it could not be read. */
    fun decode(bytes: ByteArray, password: String?): Decoded {
        val body = when (kind(bytes)) {
            Kind.UNKNOWN -> return Decoded.Failed

            Kind.ENCRYPTED -> {
                if (password.isNullOrEmpty()) return Decoded.NeedsPassword
                if (!canEncrypt()) return Decoded.Failed
                val opened = decrypt(bytes, password) ?: return Decoded.Failed
                return decode(opened, password = null)
            }

            Kind.GZIP -> runCatching { gunzip(bytes) }.getOrNull() ?: return Decoded.Failed
            Kind.TEXT -> bytes
        }

        val networks = runCatching { parse(String(body, Charsets.UTF_8)) }.getOrNull()
            ?: return Decoded.Failed
        return Decoded.Networks(networks)
    }

    /** The name a file is offered with, which is how a person tells two exports apart. */
    fun fileName(format: Format, encrypted: Boolean, stamp: String): String {
        val base = when (format) {
            Format.PLAIN -> "json"
            Format.COMPRESSED -> "json.gz"
        }
        return "shizuku-wifi-$stamp.$base" + if (encrypted) ".bin" else ""
    }

    /**
     * The networks as their JSON, so the two apps read each other's files.
     *
     * `security` is an array here because that is the shape being read back: a network can be saved
     * with more than one type, and the platform's own listing prints the second one beside the
     * first. Ours are written from the single label this app worked out, in the names they use.
     */
    internal fun text(networks: List<Network>): String {
        val array = JSONArray()
        networks.forEach { network ->
            array.put(
                JSONObject().apply {
                    put("ssid", network.ssid)
                    put("security", JSONArray().put(securityName(network.security)))
                    put("password", network.password)
                }
            )
        }
        return array.toString()
    }

    /**
     * A file's networks.
     *
     * A bare array is theirs and an object with a `networks` key is ours, and both are read: the
     * point of writing their shape is that neither app has to care which one wrote a file.
     */
    internal fun parse(text: String): List<Network> {
        val trimmed = text.trim()
        val array = when {
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> JSONObject(trimmed).optJSONArray("networks") ?: JSONArray()
        }

        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val ssid = item.optString("ssid").takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            Network(
                ssid = ssid,
                password = item.optString("password"),
                // Ours is a string and theirs is a list of them; either is read as the one label
                // this app has for a security type, which is enough to add the network back.
                security = securityLabel(item.opt("security")),
                hidden = item.optBoolean("hidden", false),
                autojoin = item.optBoolean("autojoin", true)
            )
        }
    }

    /** One security type, from either shape a file can hold, as the label this app shows. */
    internal fun securityLabel(value: Any?): String {
        val first = when (value) {
            is JSONArray -> value.optString(0)
            is String -> value
            else -> ""
        }

        // Back to the label this app shows for it, so a network reads the same after a round trip:
        // the file holds their names, and exactly one of them is not spelled the same as ours.
        val name = first.trim().uppercase().ifEmpty { "OPEN" }
        return if (name == "OPEN") "Open" else name
    }

    /** The name their files use for a label of ours. */
    internal fun securityName(label: String): String = label.trim().uppercase().ifEmpty { "OPEN" }

    internal fun gzip(content: ByteArray): ByteArray =
        ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { gzip -> gzip.write(content) }
            out.toByteArray()
        }

    internal fun gunzip(content: ByteArray): ByteArray =
        GZIPInputStream(content.inputStream()).use { it.readBytes() }

    /**
     * The container: the format byte, a fresh salt, a fresh initialisation vector, then the
     * ciphertext. The random parts are per file rather than per password, so exporting twice with
     * the same password does not produce the same file.
     */
    @RequiresApi(Build.VERSION_CODES.O)
    internal fun encrypt(content: ByteArray, password: String, format: Format): ByteArray {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, iv))

        return byteArrayOf(format.ordinal.toByte()) + salt + iv + cipher.doFinal(content)
    }

    /** What the container held, or null when the password does not open it. */
    @RequiresApi(Build.VERSION_CODES.O)
    internal fun decrypt(container: ByteArray, password: String): ByteArray? = runCatching {
        val salt = container.copyOfRange(1, 1 + SALT_BYTES)
        val iv = container.copyOfRange(1 + SALT_BYTES, 1 + SALT_BYTES + IV_BYTES)
        val body = container.copyOfRange(1 + SALT_BYTES + IV_BYTES, container.size)

        // The format byte says what the plaintext is, and the ciphertext authenticates itself: a
        // wrong password fails here rather than coming back as noise for a parser to choke on.
        val format = Format.entries[container[0].toInt()]
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, iv))
        format to cipher.doFinal(body)
    }.getOrNull()?.second

    @RequiresApi(Build.VERSION_CODES.O)
    private fun key(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        val stretched = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded

        return SecretKeySpec(stretched, ALGORITHM)
    }
}
