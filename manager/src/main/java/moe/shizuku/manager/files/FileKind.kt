package moe.shizuku.manager.files

import android.webkit.MimeTypeMap
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * What a file looks like it is, from its name.
 *
 * An extension is a claim rather than a fact, and this is used for two things that both want the
 * claim: the icon on the row, and what the file is announced as when it is opened. Neither is a
 * decision - the app that finally opens it decides what it really is - so the guess is allowed to
 * be cheap and wrong in the ordinary way.
 */
enum class FileKind {
    FOLDER,
    IMAGE,
    VIDEO,
    AUDIO,
    ARCHIVE,
    PACKAGE,
    DOCUMENT,
    CODE,
    FONT,
    OTHER
}

/** The mark on the row. Outlines throughout, which is the shape the rest of the app's icons use. */
val FileKind.icon: ImageVector
    get() = when (this) {
        FileKind.FOLDER -> Icons.Outlined.Folder
        FileKind.IMAGE -> Icons.Outlined.Image
        FileKind.VIDEO -> Icons.Outlined.Movie
        FileKind.AUDIO -> Icons.Outlined.MusicNote
        FileKind.ARCHIVE -> Icons.Outlined.FolderZip
        FileKind.PACKAGE -> Icons.Outlined.Android
        FileKind.DOCUMENT -> Icons.Outlined.Description
        FileKind.CODE -> Icons.Outlined.Code
        FileKind.FONT -> Icons.Outlined.TextFields
        FileKind.OTHER -> Icons.Outlined.InsertDriveFile
    }

fun kindOf(name: String, directory: Boolean): FileKind {
    if (directory) return FileKind.FOLDER
    return when (extension(name)) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "dng", "svg", "avif" ->
            FileKind.IMAGE

        "mp4", "mkv", "webm", "mov", "3gp", "avi", "m4v", "ts", "flv" -> FileKind.VIDEO
        "mp3", "m4a", "aac", "ogg", "opus", "flac", "wav", "amr", "mid", "mka" -> FileKind.AUDIO

        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "zst", "lz4" -> FileKind.ARCHIVE
        // Kept apart from the other archives because these are the ones the app can *do*
        // something with rather than only list.
        "apk", "apks", "apkm", "xapk", "aab", "jar" -> FileKind.PACKAGE

        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "epub", "csv", "txt", "md" ->
            FileKind.DOCUMENT

        "kt", "java", "smali", "xml", "json", "yaml", "yml", "sh", "prop", "cfg", "ini", "ts",
        "js", "py", "html", "css", "sql", "c", "cpp", "h", "gradle", "kts" -> FileKind.CODE

        "ttf", "otf", "woff", "woff2" -> FileKind.FONT
        else -> FileKind.OTHER
    }
}

/** The lowercased extension, without the dot, or an empty string when there is none. */
private fun extension(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return ""
    return name.substring(dot + 1).lowercase()
}

/**
 * What to call the file when handing it to another app.
 *
 * Our own table first, and the platform's map only for what it is left with. That order is the
 * point: the platform's map does not answer for the types this screen deals in most - an APK has
 * no entry on many builds, and the text and source files it does not know are the ones a text
 * editor should be offered for - and a file announced as the wildcard type is offered to *every*
 * app on the phone, an image viewer included. The wildcard is still the last resort, because for
 * a file nobody can name, asking is the honest answer.
 */
fun mimeOf(name: String): String {
    val extension = extension(name)
    if (extension.isEmpty()) return Wildcard
    return KnownTypes[extension]
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: Wildcard
}

/** What a file is offered as when nothing can say. */
const val Wildcard = "*/*"

/**
 * The types worth being sure about, in the order the platform gets them wrong or not at all.
 *
 * The `apk` family is the one this exists for: it is the single most common thing opened from a
 * file manager and the one whose wildcard fallback is most visible, since the package installer
 * is then only one of a screenful of apps with an opinion about it.
 */
private val KnownTypes = mapOf(
    // Installing is the only thing that can be done with these, and the installer is what claims
    // the archive type. The split-bundle extensions are the same package in a wrapper.
    "apk" to "application/vnd.android.package-archive",
    "apks" to "application/vnd.android.package-archive",
    "apkm" to "application/vnd.android.package-archive",
    "xapk" to "application/vnd.android.package-archive",

    "zip" to "application/zip",
    "jar" to "application/java-archive",
    "rar" to "application/vnd.rar",
    "7z" to "application/x-7z-compressed",
    "tar" to "application/x-tar",
    "gz" to "application/gzip",
    "tgz" to "application/gzip",
    "bz2" to "application/x-bzip2",
    "xz" to "application/x-xz",

    // Text rather than a guess per language: an editor is the app that should open all of these,
    // and the platform's map leaves most of them unclaimed.
    "txt" to "text/plain",
    "log" to "text/plain",
    "md" to "text/markdown",
    "json" to "application/json",
    "xml" to "text/xml",
    "csv" to "text/csv",
    "ini" to "text/plain",
    "cfg" to "text/plain",
    "conf" to "text/plain",
    "prop" to "text/plain",
    "sh" to "text/plain",
    "kt" to "text/plain",
    "kts" to "text/plain",
    "java" to "text/plain",
    "smali" to "text/plain",
    "yaml" to "text/plain",
    "yml" to "text/plain",
    "sql" to "text/plain",
    "py" to "text/plain",
    "js" to "text/plain",
    "ts" to "text/plain",
    "html" to "text/html",
    "css" to "text/css",
    "c" to "text/plain",
    "cpp" to "text/plain",
    "h" to "text/plain"
)
