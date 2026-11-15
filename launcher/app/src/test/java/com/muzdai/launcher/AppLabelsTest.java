package com.muzdai.launcher;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;

public class AppLabelsTest {

    @Test
    public void cleanStripsFormattingAndBounds() {
        assertEquals("Netflix", AppLabels.clean("Netflix‮", "io.example"));
        assertEquals("a b", AppLabels.clean("a\n\nb", "io.example"));
        assertEquals("a b c", AppLabels.clean("  a \t b c  ", "io.example"));
        assertEquals("Zero width", AppLabels.clean("Zero‍ width​", "io.example"));
        assertEquals("Retro", AppLabels.clean("Retro", "io.example"));
        assertEquals("io.example", AppLabels.clean("", "io.example"));
        assertEquals("io.example", AppLabels.clean("‮\n\u0000", "io.example"));
        assertEquals("io.example", AppLabels.clean(null, "io.example"));
        char[] big = new char[100];
        Arrays.fill(big, 'x');
        assertEquals(40, AppLabels.clean(new String(big), "io.example").length());
        // 39 letters then an emoji (a surrogate pair): the pair does not fit in 40 and is left out whole.
        String emoji = new String(Character.toChars(0x1F3AE));
        assertEquals(39, AppLabels.clean(new String(big, 0, 39) + emoji + "y", "io.example").length());
        assertEquals(new String(big, 0, 38) + emoji, AppLabels.clean(new String(big, 0, 38) + emoji + "y", "io.example"));
    }
}
