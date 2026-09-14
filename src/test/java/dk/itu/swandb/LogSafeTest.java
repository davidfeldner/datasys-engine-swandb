package dk.itu.swandb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the log-token sanitizer. The CSV log format is
 * comma-delimited into exactly seven values, so no rendered token may
 * ever contain a comma.
 */
class LogSafeTest {

    @Test
    void replacesSingleComma() {
        assertEquals("A;B", LogSafe.value("A,B"));
    }

    @Test
    void replacesEveryComma() {
        assertEquals("a;b;c;d", LogSafe.value("a,b,c,d"));
    }

    @Test
    void nullRendersAsNullLiteral() {
        assertEquals("null", LogSafe.value(null));
    }

    @Test
    void numbersPassThroughUnchanged() {
        assertEquals("12", LogSafe.value(12L));
        assertEquals("-42", LogSafe.value(-42L));
        assertEquals("23.5", LogSafe.value(23.5));
        assertEquals("GREATER_THAN", LogSafe.value(Comparison.GREATER_THAN));
    }

    @Test
    void plainStringsPassThroughUnchanged() {
        assertEquals("Copenhagen", LogSafe.value("Copenhagen"));
    }

    @Test
    void noCommaSurvivesAnyRenderedToken() {
        Object[] samples = {
                "a,b", "Copenhagen,DK", 12L, 23.5, null, Comparison.EQUALS,
                "trailing,", ",leading", ",,,",
        };
        for (Object sample : samples) {
            assertFalse(LogSafe.value(sample).contains(","),
                    "rendered token still contains a comma: " + LogSafe.value(sample));
        }
    }

    @Test
    void sanitizedLineStillSplitsIntoSevenValues() {
        // Mirrors the shape StorageEngine logs for a per-partition decision.
        String line = "2027-02-14 12:01:15.318,sess,0,1,DEBUG,StorageEngine,"
                + "table=" + LogSafe.value("tr,ips")
                + " column=" + LogSafe.value("city")
                + " comparison=" + LogSafe.value(Comparison.EQUALS)
                + " const=" + LogSafe.value("A,B")
                + " partition=0 min=" + LogSafe.value("Aalborg")
                + " max=" + LogSafe.value("Roskilde")
                + " decision=PRUNED";
        assertEquals(7, line.split(",", -1).length);
    }
}
