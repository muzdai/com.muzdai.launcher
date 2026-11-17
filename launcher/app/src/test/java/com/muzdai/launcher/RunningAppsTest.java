package com.muzdai.launcher;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RunningAppsTest {
    private static final Set<String> APPS = new HashSet<>(Arrays.asList(
            "com.muzdai.launcher", "com.tcl.tv", "com.spotify.tv.android", "com.google.android.youtube.tv",
            "ru.yourok.torrserve", "com.google.android.youtube.tvmusic", "tv.emby.embyatv"));

    /** Taken from the TCL 65C8K (Android 14) dump, shortened. */
    private static final String DUMP = "ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)\n"
            + "mRecentsUid=-1\n"
            + "  Recent tasks:\n"
            + "  * Recent #0: Task{2f3db56 #1026 type=home A=10148:com.muzdai.launcher}\n"
            + "    mActivityComponent=com.muzdai.launcher/.MainActivity\n"
            + "    Activities=[ActivityRecord{8e3b6fb u0 com.muzdai.launcher/.MainActivity t1026}]\n"
            + "  * Recent #1: Task{4dfcb84 #1029 type=standard A=10147:com.tcl.tv}\n"
            + "    mActivityComponent=com.tcl.tv/com.muzdai.iptv.ui.MainActivity\n"
            + "    Activities=[ActivityRecord{1 u0 com.tcl.tv/com.muzdai.iptv.ui.MainActivity t1029}]\n"
            + "  * Recent #2: Task{b2c19e1 #1023 type=undefined A=10064:com.spotify.tv.android}\n"
            + "    mActivityComponent=com.spotify.tv.android/com.spotify.app.androidtv.MainActivity\n"
            + "    Activities=[]\n"
            + "  * Recent #3: Task{6afc306 #527 type=undefined A=10067:com.google.android.youtube.tv.main}\n"
            + "    Activities=[]\n"
            + "  * Recent #4: Task{ff50992 #696 type=undefined A=10136:ru.yourok.torrserve:1975781960}\n"
            + "    Activities=[]\n"
            + "  * Recent #5: Task{982d760 #540 type=undefined I=com.google.android.youtube.tvmusic/com.google.android.apps.youtube.tvmusic.activity.MainActivity}\n"
            + "    Activities=[]\n"
            + "  * Recent #6: Task{982d761 #539 type=undefined I=com.google.android.youtube.tvmusic/.Other}\n"
            + "    Activities=[]\n"
            + "  * Recent #7: Task{d2bbf63 #546 type=undefined A=10141:tv.emby.embyatv}\n"
            + "    Activities=[]\n";

    private static final String PS = "NAME\ninit\ncom.muzdai.launcher\ncom.tcl.tv\n"
            + "com.google.android.youtube.tv:background\nru.yourok.torrserve\ncom.google.android.youtube.tvmusic\n";

    @Test public void listsTasksWithAnActivityOrAProcessNewestFirst() {
        List<RunningApps.Task> tasks = RunningApps.parse(DUMP, PS, APPS::contains);
        String[] pkgs = new String[tasks.size()];
        for (int i = 0; i < pkgs.length; i++) pkgs[i] = tasks.get(i).pkg;
        assertEquals(Arrays.asList("com.muzdai.launcher", "com.tcl.tv", "com.google.android.youtube.tv",
                "ru.yourok.torrserve", "com.google.android.youtube.tvmusic"), Arrays.asList(pkgs));
    }

    @Test public void anActivityMeansOnScreenAProcessOnlyMeansBackground() {
        List<RunningApps.Task> tasks = RunningApps.parse(DUMP, PS, APPS::contains);
        assertTrue(tasks.get(1).hasActivity);
        assertFalse(tasks.get(2).hasActivity);
        assertTrue(tasks.get(2).running);
    }

    @Test public void anAffinityLongerThanThePackageIsCutBack() {
        assertEquals("com.google.android.youtube.tv", RunningApps.packageOf(
                "  * Recent #3: Task{6afc306 #527 type=undefined A=10067:com.google.android.youtube.tv.main}\n",
                APPS::contains));
    }

    @Test public void olderDumpsCountActivitiesBySize() {
        assertTrue(RunningApps.hasActivity("  * Recent #0: TaskRecord{1 #5 A=com.tcl.tv U=0 StackId=1 sz=1}\n"));
        assertFalse(RunningApps.hasActivity("  * Recent #0: TaskRecord{1 #5 A=com.tcl.tv U=0 StackId=1 sz=0}\n"));
    }

    @Test public void aSingleInstanceAffinityIsRead() {
        assertEquals("tv.emby.embyatv", RunningApps.packageOf(
                "  * Recent #0: Task{1 #2 A=10087-si:tv.emby.embyatv}\n", APPS::contains));
    }

    @Test public void processSuffixesCountForThePackage() {
        assertTrue(RunningApps.processes("com.app:service\n").contains("com.app"));
    }

    @Test public void neverStopsTheLauncherSystemOrKeptApps() {
        Set<String> keep = Collections.singleton("com.example.keyboard");
        assertFalse(RunningApps.mayStop("com.muzdai.launcher", "com.muzdai.launcher", keep));
        assertFalse(RunningApps.mayStop("com.android.systemui", "com.muzdai.launcher", keep));
        assertFalse(RunningApps.mayStop("com.tcl.tvinput", "com.muzdai.launcher", keep));
        assertFalse(RunningApps.mayStop("com.tcl.factory.view", "com.muzdai.launcher", keep));
        assertFalse(RunningApps.mayStop("com.example.keyboard", "com.muzdai.launcher", keep));
        assertTrue(RunningApps.mayStop("com.spotify.tv.android", "com.muzdai.launcher", keep));
    }

    @Test public void onlyPackageNamesAreSafe() {
        assertTrue(RunningApps.safe("com.app_1.x"));
        assertFalse(RunningApps.safe("com.app; reboot"));
        assertFalse(RunningApps.safe(null));
    }

    @Test public void ownerOfAComponent() {
        assertEquals("com.app", RunningApps.owner("com.app/.Service"));
        assertEquals("", RunningApps.owner("null"));
    }
}
