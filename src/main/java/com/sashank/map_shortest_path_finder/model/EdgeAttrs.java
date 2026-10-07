package com.sashank.map_shortest_path_finder.model;

/**
 * Non-numeric road attributes carried on each in-memory graph edge, used by
 * intent-based routing to apply preferences.
 *
 * toll / lit / paved are tri-state: null means "OSM has no tag for this segment", which
 * is NOT the same as false — OSM `lit` coverage in particular is sparse, and the
 * routing layer reports that uncertainty rather than treating unknown as unlit.
 */
public record EdgeAttrs(RoadClass roadClass, Boolean toll, Boolean lit, Boolean paved) {

    /** Surface unknown. */
    public EdgeAttrs(RoadClass roadClass, Boolean toll, Boolean lit) {
        this(roadClass, toll, lit, null);
    }

    /** For edges loaded before attributes were imported, and for hand-built test graphs. */
    public static final EdgeAttrs UNKNOWN = new EdgeAttrs(RoadClass.UNKNOWN, null, null, null);
}
