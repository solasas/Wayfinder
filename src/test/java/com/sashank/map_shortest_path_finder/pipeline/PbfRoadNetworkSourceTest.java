package com.sashank.map_shortest_path_finder.pipeline;

import com.google.protobuf.ByteString;
import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openstreetmap.osmosis.osmbinary.Osmformat;
import org.openstreetmap.osmosis.osmbinary.file.BlockOutputStream;
import org.openstreetmap.osmosis.osmbinary.file.FileBlock;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Builds real .osm.pbf files (header block + data blocks, zlib-compressed, protobuf-encoded) and reads them back
 * through the production code, so the block decoding, delta coding and string tables are exercised for real.
 *
 * Box under test: 16.96–17.01 N, 81.76–81.82 E.   Nodes (id: where):
 *   1,2,3 inside (dense group) · 4 inside (plain node) · 5 inside · 6 outside, 7,8 outside (far away)
 */
class PbfRoadNetworkSourceTest {

    private static final String[] STRINGS = {"", "highway", "residential", "oneway", "yes", "footway", "primary", "building"};
    private static final int HIGHWAY = 1, RESIDENTIAL = 2, ONEWAY = 3, YES = 4, FOOTWAY = 5, PRIMARY = 6, BUILDING = 7;
    private static final String FILTER = "^(primary|residential)(_link)?$";

    @TempDir Path dir;
    private Path pbf;

    @BeforeEach
    void setUp() throws IOException {
        pbf = dir.resolve("region.osm.pbf");
        writePbf(pbf, true);
    }

    private static RegionConfig.Bbox box() {
        var b = new RegionConfig.Bbox();
        b.setSouth(16.96); b.setNorth(17.01); b.setWest(81.76); b.setEast(81.82);
        return b;
    }

    @Test
    void returnsMatchingWaysThatTouchTheBox_andEveryNodeTheyReference() {
        OsmResponse r = new PbfRoadNetworkSource(pbf).fetchRoadNetwork(box(), FILTER);

        Set<Long> wayIds = ids(r, "way");
        assertEquals(Set.of(100L, 102L), wayIds,
            "100 residential (inside), 102 primary (touches the box); 101 footway is filtered out, "
                + "103 is entirely outside, 104 is a building");
        // way 102 leaves the box: its outside node 6 must come along, or the segment could not be built
        assertEquals(Set.of(1L, 2L, 3L, 5L, 6L), ids(r, "node"));
    }

    @Test
    void decodesCoordinatesWaysAndTagsExactly() {
        OsmResponse r = new PbfRoadNetworkSource(pbf).fetchRoadNetwork(box(), FILTER);

        OsmResponse.OsmElement n2 = element(r, "node", 2);
        assertEquals(16.9700001, n2.getLat(), 1e-7);
        assertEquals(81.7700002, n2.getLon(), 1e-7);
        OsmResponse.OsmElement n6 = element(r, "node", 6);
        assertEquals(17.5, n6.getLat(), 1e-7);

        OsmResponse.OsmElement w = element(r, "way", 100);
        assertEquals(List.of(1L, 2L, 3L), w.getNodes(), "node order must survive the delta decoding");
        assertEquals(Map.of("highway", "residential", "oneway", "yes"), w.getTags());
        assertEquals(List.of(5L, 6L), element(r, "way", 102).getNodes());
    }

    @Test
    void theOutputParsesIntoAGraph_likeAnOverpassResponse() {
        OsmResponse r = new PbfRoadNetworkSource(pbf).fetchRoadNetwork(box(), FILTER);

        OsmParser.ParsedGraph g = new OsmParser().parse(r);

        // way 100 is oneway: 1→2, 2→3. way 102 (5–6): both directions, both nodes known
        assertEquals(2 + 2, g.edges().size());
        assertEquals(5, g.nodes().size());
    }

    @Test
    void aFilterThatMatchesNothingGivesAnEmptyTile() {
        OsmResponse r = new PbfRoadNetworkSource(pbf).fetchRoadNetwork(box(), "^motorway$");
        assertTrue(r.getElements().isEmpty());
    }

    @Test
    void aBoxWithNoRoadsInsideGivesAnEmptyTile() {
        var b = box();
        b.setSouth(10); b.setNorth(11); b.setWest(70); b.setEast(71);
        assertTrue(new PbfRoadNetworkSource(pbf).fetchRoadNetwork(b, FILTER).getElements().isEmpty());
    }

    @Test
    void aFileThatListsNodesAfterWays_isRejectedWithTheFix() throws IOException {
        Path unsorted = dir.resolve("unsorted.osm.pbf");
        writePbf(unsorted, false);

        var ex = assertThrows(IllegalStateException.class,
            () -> new PbfRoadNetworkSource(unsorted).fetchRoadNetwork(box(), FILTER));

        assertTrue(ex.getMessage().contains("osmium sort"), ex.getMessage());
    }

    @Test
    void aMissingFile_saysWhereToGetOne() {
        var ex = assertThrows(IllegalStateException.class,
            () -> new PbfRoadNetworkSource(dir.resolve("nope.osm.pbf")).fetchRoadNetwork(box(), FILTER));

        assertTrue(ex.getMessage().contains("not found") && ex.getMessage().contains("geofabrik"), ex.getMessage());
    }

    @Test
    void aFileThatIsNotAPbf_failsWithAReadableError() throws IOException {
        Path junk = dir.resolve("junk.osm.pbf");
        Files.writeString(junk, "this is not a protobuf file at all");

        assertThrows(RuntimeException.class, () -> new PbfRoadNetworkSource(junk).fetchRoadNetwork(box(), FILTER));
    }

    @Test
    void thePbfSourceNeedsNoPolitenessDelay() {
        assertEquals(0, new PbfRoadNetworkSource(pbf).requestDelayMillis());
    }

    // ── fixture ──────────────────────────────────────────────────────────────

    private static Set<Long> ids(OsmResponse r, String type) {
        return r.getElements().stream().filter(e -> type.equals(e.getType()))
            .map(OsmResponse.OsmElement::getId).collect(Collectors.toSet());
    }

    private static OsmResponse.OsmElement element(OsmResponse r, String type, long id) {
        return r.getElements().stream().filter(e -> type.equals(e.getType()) && e.getId() == id)
            .findFirst().orElseThrow(() -> new AssertionError(type + " " + id + " missing"));
    }

    /** degrees → PBF raw units (granularity 100 nanodegrees, offset 0 → 1e7 per degree). */
    private static long raw(double degrees) {
        return Math.round(degrees * 1e7);
    }

    private static void writePbf(Path file, boolean sorted) throws IOException {
        try (OutputStream os = Files.newOutputStream(file)) {
            BlockOutputStream out = new BlockOutputStream(os);
            out.setCompress("deflate");
            out.write(FileBlock.newInstance("OSMHeader", Osmformat.HeaderBlock.newBuilder()
                .addRequiredFeatures("OsmSchema-V0.6").addRequiredFeatures("DenseNodes").build().toByteString(), null));
            if (sorted) {
                out.write(dataBlock(denseNodes()));
                out.write(dataBlock(plainNodes()));
                out.write(dataBlock(ways()));
            } else {
                out.write(dataBlock(ways()));
                out.write(dataBlock(denseNodes()));
            }
            out.flush();
        }
    }

    private static FileBlock dataBlock(Osmformat.PrimitiveGroup group) {
        Osmformat.StringTable.Builder table = Osmformat.StringTable.newBuilder();
        for (String s : STRINGS) table.addS(ByteString.copyFrom(s, StandardCharsets.UTF_8));
        Osmformat.PrimitiveBlock block = Osmformat.PrimitiveBlock.newBuilder()
            .setStringtable(table).addPrimitivegroup(group).build();
        return FileBlock.newInstance("OSMData", block.toByteString(), null);
    }

    /** Nodes 1,2,3 (inside), 5 (inside), 6,7,8 (outside) — as one delta-coded dense group. */
    private static Osmformat.PrimitiveGroup denseNodes() {
        long[][] nodes = {
            {1, raw(16.9650000), raw(81.7650000)},
            {2, raw(16.9700001), raw(81.7700002)},
            {3, raw(16.9750000), raw(81.7750000)},
            {5, raw(16.9800000), raw(81.7800000)},
            {6, raw(17.5), raw(82.5)},
            {7, raw(40.0), raw(-3.0)},
            {8, raw(40.1), raw(-3.1)},
        };
        Osmformat.DenseNodes.Builder dense = Osmformat.DenseNodes.newBuilder();
        long id = 0, lat = 0, lon = 0;
        for (long[] n : nodes) {
            dense.addId(n[0] - id).addLat(n[1] - lat).addLon(n[2] - lon);
            id = n[0]; lat = n[1]; lon = n[2];
        }
        return Osmformat.PrimitiveGroup.newBuilder().setDense(dense).build();
    }

    /** Node 4 as a plain (non-dense) node, so both decoding paths run. */
    private static Osmformat.PrimitiveGroup plainNodes() {
        return Osmformat.PrimitiveGroup.newBuilder().addNodes(
            Osmformat.Node.newBuilder().setId(4).setLat(raw(16.9775)).setLon(raw(81.7775))).build();
    }

    private static Osmformat.PrimitiveGroup ways() {
        return Osmformat.PrimitiveGroup.newBuilder()
            .addWays(way(100, new long[]{1, 2, 3}, new int[]{HIGHWAY, ONEWAY}, new int[]{RESIDENTIAL, YES}))
            .addWays(way(101, new long[]{3, 4}, new int[]{HIGHWAY}, new int[]{FOOTWAY}))
            .addWays(way(102, new long[]{5, 6}, new int[]{HIGHWAY}, new int[]{PRIMARY}))
            .addWays(way(103, new long[]{7, 8}, new int[]{HIGHWAY}, new int[]{RESIDENTIAL}))
            .addWays(way(104, new long[]{1, 2, 3}, new int[]{BUILDING}, new int[]{YES}))
            .build();
    }

    private static Osmformat.Way.Builder way(long id, long[] refs, int[] keys, int[] vals) {
        Osmformat.Way.Builder w = Osmformat.Way.newBuilder().setId(id);
        long previous = 0;
        for (long r : refs) { w.addRefs(r - previous); previous = r; } // delta-coded
        for (int k : keys) w.addKeys(k);
        for (int v : vals) w.addVals(v);
        return w;
    }
}
