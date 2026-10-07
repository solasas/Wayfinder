package com.sashank.map_shortest_path_finder.pipeline;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OsmParserTest {

    private static OsmResponse.OsmElement node(long id, double lat, double lon) {
        var e = new OsmResponse.OsmElement();
        e.setType("node"); e.setId(id); e.setLat(lat); e.setLon(lon);
        return e;
    }

    private static OsmResponse.OsmElement way(long id, Map<String, String> tags) {
        var e = new OsmResponse.OsmElement();
        e.setType("way"); e.setId(id); e.setNodes(List.of(1L, 2L)); e.setTags(tags);
        return e;
    }

    private OsmParser.ParsedGraph parse(Map<String, String> tags) {
        var resp = new OsmResponse();
        resp.setElements(List.of(node(1, 16.98, 81.78), node(2, 16.981, 81.781), way(100, tags)));
        return new OsmParser().parse(resp);
    }

    @Test
    void roadAttributes_arePropagatedToBothDirections() {
        var g = parse(Map.of("highway", "trunk", "toll", "yes", "lit", "no"));
        assertEquals(2, g.edges().size());
        for (var e : g.edges()) {
            assertEquals("trunk", e.highway());
            assertEquals(Boolean.TRUE, e.toll());
            assertEquals(Boolean.FALSE, e.lit());
        }
    }

    @Test
    void untaggedOrUnrecognisedValues_areUnknownNotFalse() {
        var g = parse(Map.of("highway", "residential", "lit", "automatic"));
        var e = g.edges().get(0);
        assertNull(e.toll());
        assertNull(e.lit());
    }

    @Test
    void surface_mapsToPavedTriState() {
        assertEquals(Boolean.TRUE, parse(Map.of("highway", "residential", "surface", "asphalt")).edges().get(0).paved());
        assertEquals(Boolean.FALSE, parse(Map.of("highway", "residential", "surface", "gravel")).edges().get(0).paved());
        assertNull(parse(Map.of("highway", "residential", "surface", "weird-value")).edges().get(0).paved());
        assertNull(parse(Map.of("highway", "residential")).edges().get(0).paved());
    }
}
