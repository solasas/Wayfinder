package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import com.sashank.map_shortest_path_finder.pipeline.TilePlanner.Tile;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class TilePlannerTest {

    private final TilePlanner planner = new TilePlanner();

    private static RegionConfig.Bbox box(double s, double n, double w, double e) {
        var b = new RegionConfig.Bbox();
        b.setSouth(s); b.setNorth(n); b.setWest(w); b.setEast(e);
        return b;
    }

    @Test
    void singleCityConfig_isOneFullDetailTile_exactlyAsBefore() {
        RegionConfig cfg = new RegionConfig();
        cfg.setBbox(box(16.96, 17.01, 81.76, 81.82));

        List<Tile> tiles = planner.plan(cfg);

        assertEquals(1, tiles.size());
        assertEquals(RegionConfig.FULL_DETAIL_FILTER, tiles.get(0).highwayFilter());
        assertEquals(16.96, tiles.get(0).bbox().getSouth(), 1e-12);
        assertEquals(81.82, tiles.get(0).bbox().getEast(), 1e-12);
    }

    @Test
    void layers_areCorridorFirst_thenEachArea_withTheirOwnFilters() {
        RegionConfig cfg = new RegionConfig();
        cfg.setBbox(box(16.30, 18.00, 80.40, 83.60));
        cfg.getCorridor().setHighwayFilter("^(motorway|trunk)$");
        cfg.getCorridor().setTileSizeDegrees(0.5);
        var city = new RegionConfig.Area();
        city.setName("Vijayawada");
        city.setBbox(box(16.43, 16.62, 80.52, 80.78));
        city.setTileSizeDegrees(0.1);
        cfg.getAreas().add(city);

        List<Tile> tiles = planner.plan(cfg);

        List<String> layers = tiles.stream().map(Tile::layer).distinct().toList();
        assertEquals(List.of("corridor", "Vijayawada"), layers);
        assertTrue(tiles.stream().filter(t -> t.layer().equals("corridor")).allMatch(t -> t.highwayFilter().equals("^(motorway|trunk)$")));
        assertTrue(tiles.stream().filter(t -> t.layer().equals("Vijayawada")).allMatch(t -> t.highwayFilter().equals(RegionConfig.FULL_DETAIL_FILTER)));
        // 1.7° x 3.2° at 0.5° → 4 x 7 corridor tiles; 0.19° x 0.26° at 0.1° → 2 x 3 city tiles
        assertEquals(28, tiles.stream().filter(t -> t.layer().equals("corridor")).count());
        assertEquals(6, tiles.stream().filter(t -> t.layer().equals("Vijayawada")).count());
    }

    @Test
    void tilesCoverTheWholeBox_withoutGapsOrOversize() {
        List<Tile> tiles = TilePlanner.grid("x", box(16.30, 18.00, 80.40, 83.60), "^a$", 0.5);

        assertTrue(tiles.stream().allMatch(t -> t.bbox().getNorth() - t.bbox().getSouth() <= 0.5 + 1e-9));
        assertTrue(tiles.stream().allMatch(t -> t.bbox().getEast() - t.bbox().getWest() <= 0.5 + 1e-9));
        double area = tiles.stream().mapToDouble(t -> (t.bbox().getNorth() - t.bbox().getSouth()) * (t.bbox().getEast() - t.bbox().getWest())).sum();
        assertEquals(1.70 * 3.20, area, 1e-9);
        assertEquals(16.30, tiles.stream().mapToDouble(t -> t.bbox().getSouth()).min().orElseThrow(), 1e-12);
        assertEquals(18.00, tiles.stream().mapToDouble(t -> t.bbox().getNorth()).max().orElseThrow(), 1e-12);
        assertEquals(83.60, tiles.stream().mapToDouble(t -> t.bbox().getEast()).max().orElseThrow(), 1e-12);
    }

    @Test
    void keys_areUniqueAndStable_andChangeWithTheFilter() {
        List<Tile> a = TilePlanner.grid("c", box(16, 17, 80, 81), "^(a|b)$", 0.25);
        List<Tile> again = TilePlanner.grid("c", box(16, 17, 80, 81), "^(a|b)$", 0.25);
        List<Tile> otherFilter = TilePlanner.grid("c", box(16, 17, 80, 81), "^(a|b|c)$", 0.25);

        Set<String> keys = a.stream().map(Tile::key).collect(Collectors.toSet());
        assertEquals(a.size(), keys.size(), "tile keys must be unique");
        assertEquals(keys, again.stream().map(Tile::key).collect(Collectors.toSet()), "keys must be reproducible between runs");
        assertTrue(new HashSet<>(otherFilter.stream().map(Tile::key).toList()).stream().noneMatch(keys::contains),
            "a different filter must not be mistaken for an already-imported tile");
    }

    @Test
    void invalidLayers_areRejectedClearly() {
        assertThrows(IllegalArgumentException.class, () -> TilePlanner.grid("x", box(1, 1, 2, 3), "^a$", 0.1));
        assertThrows(IllegalArgumentException.class, () -> TilePlanner.grid("x", box(1, 2, 2, 3), "^a$", 0));
    }
}
