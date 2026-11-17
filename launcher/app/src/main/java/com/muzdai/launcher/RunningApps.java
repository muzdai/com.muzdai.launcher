package com.muzdai.launcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * vPro: which apps are open, for RunningAppsActivity. Android only tells an app about its own tasks, so the list
 * comes from root ({@link Root}): {@code dumpsys activity recents} gives the tasks newest first, {@code ps -A -o NAME}
 * the processes. A task counts when it still has an activity or its package still has a process. The idea comes
 * from Free My TV (github.com/simbasjungles/free-my-tv, RecentApps.java), which reads the same dump over ADB.
 *
 * Pure Java (no Android types), so the parsing is covered by JVM tests.
 */
final class RunningApps {
    private RunningApps() { }

    /** One open app, in the recents order. */
    static final class Task {
        final String pkg;
        final boolean hasActivity, running;

        Task(String pkg, boolean hasActivity, boolean running) {
            this.pkg = pkg;
            this.hasActivity = hasActivity;
            this.running = running;
        }
    }

    /** "  * Recent #3: Task{b2c19e1 #1023 type=undefined A=10064:com.spotify.tv.android}" starts a task's block. */
    private static final Pattern HEADER = Pattern.compile("Recent #\\d+:");
    /**
     * The task's affinity or intent on its header line: "A=10064:com.spotify.tv.android" (Android 8 to 10 without the
     * uid, Android 12 and 13 "10087-si:" for a singleInstance activity) or "I=com.app/.Main".
     */
    private static final Pattern HEADER_PKG = Pattern.compile("(?:A=(?:\\d+(?:-si)?:)?|I=)([\\w.]+)");
    /** The task's root activity, exact where the affinity is not ("com.google.android.youtube.tv.main"). */
    private static final Pattern COMPONENT = Pattern.compile("(?:mActivityComponent|realActivity)=\\{?([\\w.]+)/");
    /** Android 14 lists the task's live activities ("Activities=[]" when none are left). */
    private static final Pattern ACTIVITIES = Pattern.compile("Activities=\\[(.*)]");
    /** Older dumps give the activity count on the header line instead. */
    private static final Pattern SIZE = Pattern.compile("sz=(\\d+)");

    /**
     * The open apps, newest first, each package once: a task with an activity, or whose package has a process in
     * {@code ps}. {@code known} says which names are packages; an affinity longer than its package loses its last
     * parts until it is one.
     */
    static List<Task> parse(String recents, String ps, Predicate<String> known) {
        Set<String> alive = processes(ps);
        Map<String, Task> found = new LinkedHashMap<>();
        for (String block : blocks(recents)) {
            String pkg = packageOf(block, known);
            if (pkg == null || found.containsKey(pkg)) continue;
            boolean activity = hasActivity(block);
            boolean running = alive.contains(pkg);
            if (activity || running) found.put(pkg, new Task(pkg, activity, running));
        }
        return new ArrayList<>(found.values());
    }

    /** The dump cut into one text per task, the header line first. */
    static List<String> blocks(String recents) {
        List<String> out = new ArrayList<>();
        if (recents == null) return out;
        StringBuilder cur = null;
        for (String line : recents.split("\n")) {
            if (HEADER.matcher(line).find()) {
                if (cur != null) out.add(cur.toString());
                cur = new StringBuilder(line).append('\n');
            } else if (cur != null) {
                // A line indented less than a task's details ends the recents list.
                if (!line.startsWith("    ") && !line.trim().isEmpty()) {
                    out.add(cur.toString());
                    cur = null;
                } else {
                    cur.append(line).append('\n');
                }
            }
        }
        if (cur != null) out.add(cur.toString());
        return out;
    }

    /** The task's package: its root activity's when the dump has one, else from the affinity or intent. */
    static String packageOf(String block, Predicate<String> known) {
        Matcher c = COMPONENT.matcher(block);
        if (c.find() && known.test(c.group(1))) return c.group(1);
        String header = block.split("\n", 2)[0];
        Matcher m = HEADER_PKG.matcher(header);
        if (!m.find()) return null;
        String name = m.group(1);
        while (!known.test(name) && name.indexOf('.') > 0) name = name.substring(0, name.lastIndexOf('.'));
        return known.test(name) ? name : null;
    }

    /** Whether the task still has an activity: "Activities=[ActivityRecord…]" (Android 14) or "sz=1" (older). */
    static boolean hasActivity(String block) {
        Matcher a = ACTIVITIES.matcher(block);
        if (a.find()) return !a.group(1).trim().isEmpty();
        Matcher s = SIZE.matcher(block);
        return s.find() && Integer.parseInt(s.group(1)) > 0;
    }

    /** Package names with a process: "com.app" and "com.app:service" both count for com.app. */
    static Set<String> processes(String ps) {
        Set<String> out = new HashSet<>();
        if (ps == null) return out;
        for (String line : ps.split("\n")) {
            String name = line.trim();
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            if (!name.isEmpty()) out.add(name);
        }
        return out;
    }

    /**
     * Parts of the TV that are never closed, whatever is in the list: Android, the system UI and settings, TCL's
     * system services (inputs, remote, keys, picture), Google's assistant, the keyboard and this launcher.
     */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "android", "com.android.systemui", "com.android.tv.settings", "com.android.settings",
            "com.android.vending", "com.google.android.gms", "com.google.android.katniss",
            "com.google.android.tvrecommendations", "com.google.android.apps.tv.launcherx",
            "com.google.android.tungsten.setupwraith", "com.topjohnwu.magisk",
            "com.tcl.tvinput", "com.tcl.ttvs", "com.tvos", "com.tcl.system.server", "com.tcl.systemserver",
            "com.tcl.tcl_bt_rcu_service", "com.tcl.micmanager", "com.tcl.keyhelp", "com.tcl.settings",
            "com.tcl.common.shortcutmenu", "com.tcl.inputmethod.international", "com.tcl.guard",
            "com.tcl.keycustomfunctionservice", "com.tcl.globalkeyoverlay", "com.tcl.initsetup"));
    private static final Pattern CORE = Pattern.compile("com\\.android\\.(providers|server)\\..*|"
            + "com\\.(google\\.)?android\\.(permissioncontroller|packageinstaller|ext\\..*)|"
            + "(.*\\.)?(factory|factorymenu|facmenu|fac)(\\..*)?");

    /** Whether Close may force-stop this app (the launcher itself never: InputKeyService would go off for good). */
    static boolean mayStop(String pkg, String self, Set<String> keep) {
        return !pkg.equals(self) && !PROTECTED.contains(pkg) && !CORE.matcher(pkg).matches() && !keep.contains(pkg);
    }

    /** "com.app/.Service" or "com.app/com.app.Ime" → "com.app"; empty for "null" or nothing. */
    static String owner(String component) {
        String s = component == null ? "" : component.trim();
        int slash = s.indexOf('/');
        return slash > 0 ? s.substring(0, slash) : "";
    }

    /** Only package names go into a root shell command. */
    static boolean safe(String pkg) {
        return pkg != null && pkg.matches("[A-Za-z0-9_.]+");
    }
}
