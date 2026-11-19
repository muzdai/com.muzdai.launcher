package com.muzdai.launcher;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Whose music it is, on update sequences as the owner's Roon Core sends them. */
public class RoonOwnerTest {
    private static final long S = 1000;

    /** Started at 0 s: playing, the album's queue, the position from 0. */
    private static RoonOwner playingAlbum() {
        RoonOwner o = new RoonOwner();
        o.started(0);
        assertNull(o.onState("playing", 1 * S));
        assertNull(o.onQueue(Arrays.asList(17147L, 17148L, 17149L, 17150L), 1 * S));
        for (int t = 2; t <= 30; t++) assertNull(o.onSeek(t - 2, t * S));
        return o;
    }

    @Test public void theAlbumGoingOnIsOurs() {
        // The track ends: stopped for a moment, the queue moves on one, the position starts again.
        RoonOwner o = playingAlbum();
        assertNull(o.onState("stopped", 31 * S));
        assertNull(o.onQueue(Arrays.asList(17148L, 17149L, 17150L, 17151L), 31 * S));
        assertNull(o.onState("playing", 31 * S + 500));
        assertNull(o.onSeek(1, 32 * S));
        assertNull(o.tick(40 * S));
        // Roon Radio topping up a short queue at its end.
        assertNull(o.onQueue(Arrays.asList(17148L, 17149L, 17150L, 17151L, 90001L), 41 * S));
        assertTrue(o.ours());
    }

    @Test public void ourCommandsAreOurs() {
        RoonOwner o = playingAlbum();
        o.commanded(31 * S);   // "дальше"
        assertNull(o.onState("stopped", 31 * S));
        assertNull(o.onQueue(Arrays.asList(17148L, 17149L, 17150L, 17151L), 31 * S));
        assertNull(o.onState("playing", 32 * S));
        o.commanded(50 * S);   // "пауза", then "продолжи"
        assertNull(o.onState("paused", 50 * S));
        o.commanded(70 * S);
        assertNull(o.onState("playing", 70 * S));
        o.commanded(90 * S);   // "назад": the queue moves back
        assertNull(o.onQueue(Arrays.asList(17147L, 17148L, 17149L, 17150L), 90 * S));
        assertTrue(o.ours());
    }

    @Test public void aPhoneTakesOver() {
        RoonOwner o = playingAlbum();
        assertNotNull(o.onQueue(Arrays.asList(20001L, 20002L, 20003L), 40 * S));   // another album, Play Now
        assertFalse(o.ours());

        o = playingAlbum();
        assertNotNull(o.onQueue(Arrays.asList(17147L, 30000L, 17148L, 17149L), 40 * S));   // "add next" elsewhere
        o = playingAlbum();
        assertNotNull(o.onState("paused", 40 * S));                                     // paused elsewhere
        o = playingAlbum();
        assertNotNull(o.onSeek(120, 31 * S));                                            // seeked elsewhere
        assertFalse(o.ours());

        o = playingAlbum();
        assertNull(o.onState("stopped", 40 * S));
        assertNull(o.tick(43 * S));
        assertNotNull(o.tick(47 * S));                                                   // stopped for good
    }

    @Test public void notOursStaysNotOurs() {
        RoonOwner o = new RoonOwner();
        assertNull(o.onState("playing", 0));
        assertNull(o.onQueue(Arrays.asList(1L, 2L), 0));
        assertNull(o.onQueue(Arrays.asList(5L, 6L), S));
        assertFalse(o.ours());
        assertTrue(RoonOwner.follows(Arrays.asList(1L, 2L, 3L), Arrays.asList(2L, 3L, 4L), 1));
        assertFalse(RoonOwner.follows(Arrays.asList(1L, 2L, 3L), Arrays.asList(9L, 3L), 1));
    }
}
