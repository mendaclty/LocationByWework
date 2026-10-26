package org.gallonyin.weworkhk;

import org.junit.Test;

import static org.junit.Assert.*;

public class NmeaRewriterTest {
    private static final double WGS_LAT = 22.807641983032227d;
    private static final double WGS_LNG = 113.20242309570312d;

    @Test
    public void rewriteReplacesRmcCoordinatesAndKeepsChecksumValid() {
        String original = "$GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W*6A";
        String rewritten = NmeaRewriter.rewrite(original, WGS_LAT, WGS_LNG);

        assertNotEquals(original, rewritten);
        assertTrue(rewritten.startsWith("$GPRMC,123519,A,"));
        assertTrue(rewritten.contains(",2248.45852,N,11312.14539,E,"));
        verifyChecksum(rewritten);
    }

    @Test
    public void rewriteReplacesGgaCoordinatesAndFixQuality() {
        String original = "$GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,*47";
        String rewritten = NmeaRewriter.rewrite(original, WGS_LAT, WGS_LNG);

        assertTrue(rewritten.startsWith("$GPGGA,123519,2248.45852,N,11312.14539,E,1,"));
        verifyChecksum(rewritten);
    }

    @Test
    public void rewriteReplacesGllCoordinates() {
        String original = "$GPGLL,4807.038,N,01131.000,E,123519,A*2A";
        String rewritten = NmeaRewriter.rewrite(original, WGS_LAT, WGS_LNG);

        assertTrue(rewritten.startsWith("$GPGLL,2248.45852,N,11312.14539,E,"));
        verifyChecksum(rewritten);
    }

    @Test
    public void unsupportedSentenceTypesPassThroughUnchanged() {
        String gsv = "$GPGSV,3,1,11,03,03,111,00,04,15,270,00,06,01,010,00,13,06,292,00*45";
        assertEquals(gsv, NmeaRewriter.rewrite(gsv, WGS_LAT, WGS_LNG));
        assertNull(NmeaRewriter.rewrite(null, WGS_LAT, WGS_LNG));
    }

    private static void verifyChecksum(String sentence) {
        int star = sentence.lastIndexOf('*');
        assertTrue(star > 0);
        int expected = 0;
        for (int i = 1; i < star; i++) expected ^= sentence.charAt(i);
        String actual = sentence.substring(star + 1, Math.min(star + 3, sentence.length()));
        assertEquals(String.format("%02X", expected), actual);
    }
}
