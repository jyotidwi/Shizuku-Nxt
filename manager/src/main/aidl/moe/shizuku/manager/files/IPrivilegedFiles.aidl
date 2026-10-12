// The file operations this app asks for as the shell.
//
// The point of every one of these is the uid they run under: a user service started by Shizuku
// runs as the adb shell, so `open` hands back a real descriptor the app could not have opened
// itself - Android/data, Android/obb, /system, /proc - and the descriptor is seekable, which a
// pipe from a `cat` would not be.
//
// Deliberately four methods and no more. Anything that can be built out of them (copy, zip,
// search) belongs in the app, where the progress and the conflict handling are; what cannot be
// built out of them - reaching a file this uid may not reach - is what is here.
package moe.shizuku.manager.files;

import android.os.ParcelFileDescriptor;

interface IPrivilegedFiles {

    /** Opens [path] for reading or writing, as the shell. */
    ParcelFileDescriptor open(String path, int mode);

    /** Runs [command] under `sh -c` and returns its combined output. */
    String exec(String command);

    /** Whether anything is at [path]. */
    boolean exists(String path);

    /** Creates [path] and any parent it is missing. */
    boolean mkdir(String path);

    /**
     * Moves [from] to [to] within one filesystem, which is instant where a copy would not be.
     *
     * Nothing is done recursively: a rename that would cross a filesystem fails here, and the
     * caller falls back to copying - which is the only place the difference is visible, and the
     * reason this returns whether it worked rather than throwing.
     */
    boolean rename(String from, String to);

    /** Removes [path], and everything under it when [recursive] is set. */
    boolean delete(String path, boolean recursive);

    /**
     * One record per entry of the directory [path], separated by newlines.
     *
     * A record is `name \t d|f \t size \t modified`, with backslash, tab and newline in the name
     * escaped as `\\`, `\t` and `\n` - the tab being what keeps a name with a tab in it from
     * forging a field. Names come first so that everything after the third tab is the name.
     */
    String list(String path);

    /** Stops the service. Unbinding alone only drops the connection, not the process. */
    void destroy();
}
