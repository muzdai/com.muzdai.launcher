package com.muzdai.launcher;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * vPro: shell commands as root through Magisk's {@code su}. Magisk asks once whether the launcher may have root
 * (or the grant is put in its policy table from adb). Blocking: call it off the main thread. Callers only pass
 * fixed commands and package names checked with {@link RunningApps#safe}.
 */
final class Root {
    private static final String TAG = "Root";
    private static final long TIMEOUT_S = 15;

    private Root() { }

    /** The command's output (stdout), or null when su is missing, refused or the command timed out. */
    static String run(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("su").redirectErrorStream(false).start();
            try (OutputStream in = p.getOutputStream()) {
                in.write((command + "\nexit\n").getBytes(StandardCharsets.UTF_8));
            }
            final Process proc = p;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> copy(proc.getInputStream(), out), "root-out");
            Thread drain = new Thread(() -> copy(proc.getErrorStream(), new ByteArrayOutputStream()), "root-err");
            reader.start();
            drain.start();
            if (!p.waitFor(TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "timeout");
                return null;
            }
            reader.join(2000);
            if (p.exitValue() != 0 && out.size() == 0) {
                Log.w(TAG, "su refused or failed: " + p.exitValue());
                return null;
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.w(TAG, "su: " + e);
            return null;
        } finally {
            if (p != null) p.destroy();
        }
    }

    private static void copy(InputStream in, ByteArrayOutputStream out) {
        byte[] buf = new byte[16 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception ignored) {
        }
    }
}
