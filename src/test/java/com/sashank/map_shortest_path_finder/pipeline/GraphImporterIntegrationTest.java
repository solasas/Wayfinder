package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.support.EmbeddedPg;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The whole import loop — planning, fetching (mocked Overpass), parsing, storing, progress, resume — against real
 * PostgreSQL. The mocked fetcher returns, for each tile, two nodes and one way derived from the tile's position, so
 * every tile contributes distinct data and the expected totals are easy to state.
 */
class GraphImporterIntegrationTest {

    private JdbcTemplate jdbc;
    private TileImporter tileImporter;
    private OsmDataFetcher fetcher;
    private RegionConfig config;
    private GraphImporter importer;
    private final List<String> fetchedTiles = new ArrayList<>();

    private static RegionConfig.Bbox box(double s, double n, double w, double e) {
        var b = new RegionConfig.Bbox();
        b.setSouth(s); b.setNorth(n); b.setWest(w); b.setEast(e);
        return b;
    }

    /** Stable id for a tile from its south-west corner (0.1° grid). */
    private static long tileNumber(RegionConfig.Bbox b) {
        return Math.round((b.getSouth() - 16.0) / 0.1) * 100 + Math.round((b.getWest() - 80.0) / 0.1);
    }

    private static OsmResponse tileData(RegionConfig.Bbox b) {
        long k = tileNumber(b);
        var n1 = new OsmResponse.OsmElement(); n1.setType("node"); n1.setId(10 * k + 1); n1.setLat(b.getSouth() + 0.01); n1.setLon(b.getWest() + 0.01);
        var n2 = new OsmResponse.OsmElement(); n2.setType("node"); n2.setId(10 * k + 2); n2.setLat(b.getSouth() + 0.02); n2.setLon(b.getWest() + 0.02);
        var w = new OsmResponse.OsmElement(); w.setType("way"); w.setId(5_000 + k); w.setNodes(List.of(10 * k + 1, 10 * k + 2)); w.setTags(Map.of("highway", "primary", "oneway", "yes"));
        var r = new OsmResponse();
        r.setElements(List.of(n1, n2, w));
        return r;
    }

    @BeforeEach
    void setUp() {
        EmbeddedPg.freshSchema();
        jdbc = EmbeddedPg.jdbc();
        tileImporter = new TileImporter(jdbc, EmbeddedPg.txManager());

        config = new RegionConfig();
        config.setName("Test region");
        config.setBbox(box(16.0, 16.2, 80.0, 80.2));
        var area = new RegionConfig.Area();
        area.setName("A");
        area.setBbox(box(16.0, 16.2, 80.0, 80.2));       // 0.2° at 0.1° tiles → 4 tiles
        area.setTileSizeDegrees(0.1);
        config.getAreas().add(area);
        config.getImporter().setRequestDelayMillis(0);

        fetcher = mock(OsmDataFetcher.class);
        when(fetcher.fetchRoadNetwork(any(), anyString())).thenAnswer(inv -> {
            RegionConfig.Bbox b = inv.getArgument(0);
            fetchedTiles.add(b.toOverpassFormat());
            return tileData(b);
        });
        importer = newImporter();
    }

    private GraphImporter newImporter() {
        GraphImporter g = new GraphImporter();
        ReflectionTestUtils.setField(g, "regionConfig", config);
        ReflectionTestUtils.setField(g, "tilePlanner", new TilePlanner());
        ReflectionTestUtils.setField(g, "osmDataFetcher", fetcher);
        ReflectionTestUtils.setField(g, "osmParser", new OsmParser());
        ReflectionTestUtils.setField(g, "tileImporter", tileImporter);
        ReflectionTestUtils.setField(g, "jdbc", jdbc);
        return g;
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    @Test
    void importsEveryTile_andRecordsProgress() throws Exception {
        importer.run();

        assertEquals(4, fetchedTiles.size());
        assertEquals(8, count("nodes"));
        assertEquals(4, count("edges"));
        assertEquals(4, count("import_tiles"));
    }

    @Test
    void aCompletedImport_isNotRepeated() throws Exception {
        importer.run();
        fetchedTiles.clear();

        newImporter().run();

        assertTrue(fetchedTiles.isEmpty(), "a finished import must not hit Overpass again");
        assertEquals(8, count("nodes"));
    }

    @Test
    void afterAFailureHalfWayThrough_rerunningResumesWithOnlyTheRemainingTiles() throws Exception {
        // fail on the third tile (after two were stored)
        var calls = new int[1];
        doAnswer(inv -> {
            RegionConfig.Bbox b = inv.getArgument(0);
            if (++calls[0] == 3) throw new IllegalStateException("Overpass did not deliver tile 3");
            fetchedTiles.add(b.toOverpassFormat());
            return tileData(b);
        }).when(fetcher).fetchRoadNetwork(any(), anyString());
        assertThrows(IllegalStateException.class, () -> importer.run());
        assertEquals(2, count("import_tiles"));
        assertEquals(4, count("nodes"));

        // a NEW process (fresh memory, working network) runs the same command again
        List<String> done = List.copyOf(fetchedTiles);
        fetchedTiles.clear();
        doAnswer(inv -> {
            RegionConfig.Bbox b = inv.getArgument(0);
            fetchedTiles.add(b.toOverpassFormat());
            return tileData(b);
        }).when(fetcher).fetchRoadNetwork(any(), anyString());
        tileImporter = new TileImporter(jdbc, EmbeddedPg.txManager());
        newImporter().run();

        assertEquals(2, fetchedTiles.size(), "only the two unfinished tiles");
        assertTrue(fetchedTiles.stream().noneMatch(done::contains), "no finished tile may be fetched again");
        assertEquals(8, count("nodes"));
        assertEquals(4, count("edges"));
        assertEquals(4, count("import_tiles"));
    }

    @Test
    void forceReimport_wipesAndFetchesEverythingAgain() throws Exception {
        importer.run();
        fetchedTiles.clear();

        importer.run("--force-reimport");

        assertEquals(4, fetchedTiles.size());
        assertEquals(8, count("nodes"));
        assertEquals(4, count("import_tiles"));
    }

    @Test
    void aGraphImportedWithoutProgressTracking_isLeftAloneUnlessForced() throws Exception {
        jdbc.update("INSERT INTO nodes (osm_id, lat, lng) VALUES (1, 16.1, 80.1)");   // as the old importer would have left it

        importer.run();

        assertTrue(fetchedTiles.isEmpty());
        assertEquals(1, count("nodes"));
    }

    @Test
    void addingAnAreaLater_fetchesOnlyTheNewTiles() throws Exception {
        importer.run();
        fetchedTiles.clear();

        var more = new RegionConfig.Area();
        more.setName("B");
        more.setBbox(box(16.0, 16.1, 80.2, 80.3));       // one new tile
        more.setTileSizeDegrees(0.1);
        config.getAreas().add(more);
        tileImporter = new TileImporter(jdbc, EmbeddedPg.txManager());
        newImporter().run();

        assertEquals(1, fetchedTiles.size());
        assertEquals(10, count("nodes"));
        assertEquals(5, count("import_tiles"));
    }

    @Test
    void aWayThatBothLayersReturn_isImportedOnce() throws Exception {
        // corridor layer + a city layer over the same ground: the same way comes back from both
        config.getCorridor().setHighwayFilter("^(primary)$");
        config.getCorridor().setTileSizeDegrees(0.2);     // 1 corridor tile covering the area's 4 tiles
        doAnswer(inv -> {
            RegionConfig.Bbox b = inv.getArgument(0);
            fetchedTiles.add(b.toOverpassFormat());
            // every request, whatever its size, reports the SAME way (id 5000, nodes 1 and 2)
            var n1 = new OsmResponse.OsmElement(); n1.setType("node"); n1.setId(1); n1.setLat(16.05); n1.setLon(80.05);
            var n2 = new OsmResponse.OsmElement(); n2.setType("node"); n2.setId(2); n2.setLat(16.06); n2.setLon(80.06);
            var w = new OsmResponse.OsmElement(); w.setType("way"); w.setId(5000); w.setNodes(List.of(1L, 2L)); w.setTags(Map.of("highway", "primary", "oneway", "yes"));
            var r = new OsmResponse(); r.setElements(List.of(n1, n2, w));
            return r;
        }).when(fetcher).fetchRoadNetwork(any(), anyString());

        importer.run();

        assertEquals(5, fetchedTiles.size());             // 1 corridor + 4 area tiles
        assertEquals(2, count("nodes"));
        assertEquals(1, count("edges"), "the shared way must yield its edge exactly once");
    }
}
