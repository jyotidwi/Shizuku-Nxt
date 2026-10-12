package moe.shizuku.manager.files

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.annotation.Keep
import java.io.File

/**
 * The other side of [IPrivilegedFiles], running in a process Shizuku started for this app.
 *
 * Nothing here is an Android component: Shizuku loads this class into an `app_process` of its own
 * and calls it by name, which is why it must stay public with a constructor it can find, and why
 * the supplied [Context] is not kept - it is a stub of one rather than a real application context,
 * and framework calls made through it do not behave.
 *
 * What the class is for is the uid it runs under. That process is the adb shell, so `File(path)`
 * here sees the directories the app cannot: `Android/data` and `Android/obb` (the shell has the
 * supplementary group they are opened to), `/system`, `/proc`, `/storage`. `/data/data` stays
 * closed even here - the shell's SELinux domain is denied on it - and nothing in this class
 * pretends otherwise; a refusal comes back as the platform's own exception.
 *
 * Read and write are both offered because an fd is an fd: the transport decides what may be
 * opened, and the screens that offer it decide what a person can reach. Milestone one only reads.
 */
@Keep
class PrivilegedFilesService() : IPrivilegedFiles.Stub() {

    /** Shizuku may construct a user service either way, so both are accepted. */
    @Suppress("UNUSED_PARAMETER")
    constructor(context: Context) : this()

    override fun open(path: String, mode: Int): ParcelFileDescriptor =
        ParcelFileDescriptor.open(File(path), mode)

    /**
     * `sh -c`, with the two streams merged.
     *
     * Merged on purpose: the caller gets one string either way, and leaving stderr as a second
     * stream it never reads is how a command that writes more than a pipe's worth of errors
     * deadlocks the service it is running in. A command that fails says so by throwing, with its
     * own output as the message, so the app does not show a failure as an empty success.
     */
    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val code = process.waitFor()
        if (code != 0) {
            throw IllegalStateException(
                output.trim().ifEmpty { "the command exited with $code" }.take(MAX_MESSAGE)
            )
        }
        return output
    }

    /**
     * A directory, as records rather than as a shell listing.
     *
     * Parsing `ls` would be shorter and wrong: a name may contain anything including the spaces
     * and newlines the columns are separated by. Reading the directory in-process gives the names
     * the filesystem actually holds, and the escaping in [escape] keeps a name from forging a
     * record of its own.
     */
    override fun list(path: String): String {
        val entries = File(path).listFiles() ?: return ""

        // Directories first, then by name, so the screen and the service agree on the order and
        // the app is not sorting several hundred records it just paid a binder call for.
        val sorted = entries.sortedWith(
            compareByDescending<File> { it.isDirectory }
                .thenBy { it.name.lowercase() }
        )

        return sorted.joinToString("\n") { entry ->
            val size = if (entry.isDirectory) 0L else runCatching { entry.length() }.getOrDefault(0L)
            escape(entry.name) + '\t' +
                (if (entry.isDirectory) 'd' else 'f') + '\t' +
                size + '\t' +
                entry.lastModified()
        }
    }

    override fun exists(path: String): Boolean = File(path).exists()

    override fun mkdir(path: String): Boolean {
        val file = File(path)
        return file.isDirectory || file.mkdirs()
    }

    override fun rename(from: String, to: String): Boolean = File(from).renameTo(File(to))

    /**
     * Deletes, deepest first.
     *
     * A directory only goes when it is empty, so the children have to be taken before it - and
     * each one's own answer matters, because a tree that could not be finished has to say so
     * rather than report success over the part that went.
     */
    override fun delete(path: String, recursive: Boolean): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        if (file.isDirectory) {
            if (!recursive) return file.delete()
            file.listFiles()?.forEach { child ->
                if (!delete(child.path, true)) return false
            }
        }
        return file.delete()
    }

    /**
     * Leaves the process.
     *
     * Unbinding the service only drops the connection to it; the process keeps running until it
     * is told to stop, which is what this is for.
     */
    override fun destroy() {
        System.exit(0)
    }

    private companion object {
        /** Well under the binder's reply limit, so a long error cannot fail the call itself. */
        const val MAX_MESSAGE = 16 * 1024

        /**
         * Backslash first, or the escapes added for tab and newline would be escaped again.
         */
        fun escape(name: String): String = name
            .replace("\\", "\\\\")
            .replace("\t", "\\t")
            .replace("\n", "\\n")
    }
}
