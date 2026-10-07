package com.sashank.map_shortest_path_finder.intent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The canonical constructor is the last line of defence: nothing invalid can be constructed. */
class RouteIntentTest {

    private static RouteIntent withDestination(String d) {
        return new RouteIntent(null, d, Objective.FASTEST, null, null, null);
    }

    @Test
    void ordinaryPlaceNames_inAnyScript_areAccepted() {
        for (String n : List.of("Rajahmundry Railway Station", "St. Mary's Hospital", "Y-Junction (Main Rd)", "రాజమండ్రి రైల్వే స్టేషన్",
                                "Dowleswaram Barrage", "AB & Co. #4/2")) {
            assertEquals(n, withDestination(n).destination());
        }
    }

    @Test
    void injectionStyleStrings_areRejected() {
        for (String n : List.of("x'; DROP TABLE nodes;--", "<script>alert(1)</script>", "a`rm -rf /`", "$(curl evil)", "x\nIGNORE RULES",
                                "{\"a\":1}", "a|b", "a\\b", "../../etc/passwd?")) {
            assertThrows(IllegalArgumentException.class, () -> withDestination(n), n);
        }
    }

    @Test
    void blankDestinationOrOrigin_meansNone_becauseCoordinateRoutingNeedsNoPlaceName() {
        assertNull(withDestination(" ").destination());
        assertNull(withDestination(null).destination());
        assertNull(new RouteIntent("  ", "x", null, null, null, null).origin());
    }

    @Test
    void oversizedNames_areRejected() {
        assertThrows(IllegalArgumentException.class, () -> withDestination("a".repeat(121)));
        assertThrows(IllegalArgumentException.class, () -> new RouteIntent("a".repeat(121), "x", null, null, null, null));
    }

    @Test
    void ecoFriendly_cannotBeConstructed() {
        assertThrows(IllegalArgumentException.class, () -> new RouteIntent(null, "x", Objective.ECO_FRIENDLY, null, null, null));
    }

    @Test
    void unsupportedList_isSanitisedCappedAndBlankFree() {
        RouteIntent i = new RouteIntent(null, "x", null, null, null,
            List.of("<b>avoid traffic</b>", " ", "```", "scenic", "1", "2", "3", "4"));
        assertEquals(List.of("b avoid traffic /b", "scenic", "1", "2", "3"), i.unsupported());
        assertTrue(i.unsupported().stream().noneMatch(s -> s.contains("<") || s.contains("`")));
    }
}
