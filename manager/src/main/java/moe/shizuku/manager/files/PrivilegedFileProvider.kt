package moe.shizuku.manager.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Hands a privileged file to another app.
 *
 * A file manager that can only look at a file is not much of one: the reason to open
 * `Android/data/com.game/files/save.dat` is to do something with it, and that means handing it to
 * whatever app owns that kind of file. The usual way is `FileProvider`, and it does not work here:
 * it opens the path in *this* process, and this process is the one that cannot read it. The
 * descriptor exists on the other side of the binder, in the shell's process.
 *
 * So this is a provider of our own whose [openFile] does nothing but ask for that descriptor. The
 * platform calls it in our process, we ask [PrivilegedFiles] for an fd, and the fd we hand back was
 * opened by the shell - which is exactly the point. The receiving app reads a file it has no
 * business being able to read, through a grant that lasts as long as the intent does.
 *
 * Read-only on purpose, and narrow: a caller names a path, and every path costs the shell's own
 * permission check on the way through.
 */
class PrivilegedFileProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    @Throws(FileNotFoundException::class)
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        // Writing through a grant is a different decision from reading through one, and nothing
        // this app offers needs it yet.
        if (mode != "r") throw FileNotFoundException("this provider only reads")

        val path = uri.path?.removePrefix("/")?.takeIf { it.isNotEmpty() }
            ?: throw FileNotFoundException("no path in $uri")

        return try {
            PrivilegedFiles.openRead(path)
        } catch (e: IOException) {
            // The platform's own refusal is the message worth passing on: "permission denied" says
            // what happened, where a generic failure would not.
            throw FileNotFoundException(e.message)
        } catch (e: SecurityException) {
            throw FileNotFoundException(e.message)
        }
    }

    /**
     * What the file is, for anything that asks the provider rather than the intent.
     *
     * Not null, which is the usual answer for a provider that serves files without knowing what
     * they are: a null here is indistinguishable from "no idea", and the system's own resolver
     * falls back to offering every app on the phone, an image viewer included, for a file whose
     * type it could not name.
     */
    override fun getType(uri: Uri): String = mimeOf(uri.path?.substringAfterLast('/').orEmpty())

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read only")
}
