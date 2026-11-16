package com.muzdai.launcher;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class PlayStoreInfoTest {
    @Test public void titleDropsPlaysOwnPartWhereverItIs() {
        assertEquals("Spotify: музыка и подкасты", PlayStoreInfo.title(
                "<meta property=\"og:title\" content=\"Приложения в Google Play – Spotify: музыка и подкасты\">"));
        assertEquals("Deezer & Co", PlayStoreInfo.title(
                "<meta property=\"og:title\" content=\"Deezer &amp; Co – Apps on Google Play\">"));
        assertNull(PlayStoreInfo.title("<html></html>"));
    }

    @Test public void imageIsAskedForAtTheGivenSize() {
        assertEquals("https://play-lh.googleusercontent.com/abc=s256",
                PlayStoreInfo.sized("https://play-lh.googleusercontent.com/abc=w240-h480-rw", 256));
        assertEquals("https://play-lh.googleusercontent.com/abc=s256",
                PlayStoreInfo.sized("https://play-lh.googleusercontent.com/abc", 256));
    }
}
