package com.muzdai.launcher;

import java.util.ArrayList;
import java.util.List;

/**
 * Whether the music in Roon's zone is still what the launcher started. Roon does not say who did what, so this
 * follows the zone's updates against the launcher's own commands:
 * <ol>
 * <li>the track changing to the next one in the queue (the album going on) is ours;</li>
 * <li>a change within a few seconds of a command from here (next, previous, play now) is ours;</li>
 * <li>the queue replaced or changed other than by moving on one track (an album picked on a phone, "add next") is
 * not;</li>
 * <li>pause or play with no command from here is not;</li>
 * <li>a jump in the track's position with no command from here (seeking on a phone) is not;</li>
 * </ol>
 * and a zone stopped for a while is not ours either. Pure, tested on the JVM; times in milliseconds.
 */
final class RoonOwner {
    /** How long after a command from here the zone's changes are taken as its effect. */
    static final long COMMAND_MS = 10000;
    /** A position this far from where it should be is a seek. */
    static final double JUMP_S = 4;
    /** Stopped this long: over. */
    static final long STOPPED_MS = 6000;
    /** Around a track change the position starts again: no seek is judged then. */
    private static final long TRACK_CHANGE_MS = 3000;

    private boolean ours;
    private long lastCommand = Long.MIN_VALUE / 2;
    private String state;
    private long stoppedSince = -1;
    private List<Long> queue;
    private long queueChanged = Long.MIN_VALUE / 2;
    private double seek = -1;
    private long seekAt;

    boolean ours() {
        return ours;
    }

    /** The launcher started music in the zone. */
    void started(long now) {
        ours = true;
        lastCommand = now;
        stoppedSince = -1;
    }

    /** Any other command from here (pause, next, volume…). */
    void commanded(long now) {
        lastCommand = now;
    }

    /** Ours no more (handed back to the TV, or for a reason below). */
    void drop() {
        ours = false;
    }

    /** Ours from an earlier run of the launcher (kept in settings): the next updates set what is expected. */
    void restore(boolean wasOurs) {
        ours = wasOurs;
        state = null;
        queue = null;
        seek = -1;
    }

    private boolean recent(long now) {
        return now - lastCommand <= COMMAND_MS;
    }

    /** The zone's state; returns why it is not ours any more, or null. */
    String onState(String s, long now) {
        String before = state;
        if ("stopped".equals(s)) {
            if (stoppedSince < 0) stoppedSince = now;
        } else {
            stoppedSince = -1;
        }
        if (!"playing".equals(s) && !"paused".equals(s)) return null;   // loading, stopped: in between
        state = s;
        if (!ours || before == null || before.equals(s) || recent(now)) return null;
        // "stopped" between them (the moment of a track change) is not a pause.
        return lose("playing".equals(s) ? "played with no command from here" : "paused with no command from here");
    }

    /** A while later: a zone that stays stopped is over. */
    String tick(long now) {
        if (ours && stoppedSince >= 0 && now - stoppedSince >= STOPPED_MS && !recent(now)) return lose("stopped");
        return null;
    }

    /** The queue's first items by id (the first is the track playing); returns why it is not ours, or null. */
    String onQueue(List<Long> ids, long now) {
        List<Long> before = queue;
        queue = new ArrayList<>(ids);
        if (before == null || before.equals(ids)) return null;
        queueChanged = now;
        if (!ours || recent(now)) return null;
        // Moved on one track (or the same with more at the end, Roon Radio topping it up): ours.
        if (follows(before, ids, 1) || follows(before, ids, 0)) return null;
        return lose("the queue was changed elsewhere");
    }

    /** ids from {@code before} moved on by {@code shift}, where they overlap; at least the first must be there. */
    static boolean follows(List<Long> before, List<Long> after, int shift) {
        if (after.isEmpty() || before.size() <= shift) return false;
        int overlap = Math.min(after.size(), before.size() - shift);
        for (int i = 0; i < overlap; i++) if (!after.get(i).equals(before.get(i + shift))) return false;
        return overlap > 0;
    }

    /** The track's position in seconds; returns why it is not ours, or null. */
    String onSeek(double s, long now) {
        double before = seek;
        long at = seekAt;
        seek = s;
        seekAt = now;
        if (!ours || before < 0 || recent(now) || now - queueChanged <= TRACK_CHANGE_MS) return null;
        double expected = "playing".equals(state) ? before + (now - at) / 1000.0 : before;
        if (Math.abs(s - expected) <= JUMP_S) return null;
        // From the end of a track to the start of the next one before the queue says so: a track change.
        if (s <= JUMP_S) return null;
        return lose("the position jumped from " + Math.round(before) + " to " + Math.round(s) + " s");
    }

    private String lose(String why) {
        ours = false;
        return why;
    }
}
