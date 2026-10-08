package com.sashank.map_shortest_path_finder.config;

import com.sashank.map_shortest_path_finder.pipeline.TilePlanner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Binds the project's real application.properties into RegionConfig. A mistyped key (region.area[0] instead of
 * region.areas[0], highway_filter instead of highway-filter) is silently ignored by Spring and the default wins,
 * which here would quietly import the wrong thing — so check what actually bound.
 */
class RegionPropertiesTest {

    private void withRegion(java.util.function.Consumer<RegionConfig> check) {
        new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .run(ctx -> check.accept(Binder.get(ctx.getEnvironment()).bind("region", RegionConfig.class).get()));
    }

    @Test
    void theConfiguredRegionIsTheThreeCityCorridor() {
        withRegion(cfg -> {
            assertTrue(cfg.getCorridor().isEnabled(), "corridor filter must bind");
            assertEquals(0.5, cfg.getCorridor().getTileSizeDegrees());
            assertEquals(List.of("Vijayawada", "Rajahmundry", "Visakhapatnam"),
                cfg.getAreas().stream().map(RegionConfig.Area::getName).toList());
            assertTrue(cfg.getMaxSnapMeters() > 0, "the bbox contains sea and countryside: snapping must be bounded");
        });
    }

    @Test
    void everyCityBoxIsWellFormedAndInsideTheOverallBox() {
        withRegion(cfg -> {
            RegionConfig.Bbox all = cfg.getBbox();
            for (RegionConfig.Area a : cfg.getAreas()) {
                RegionConfig.Bbox b = a.getBbox();
                assertTrue(b.getSouth() < b.getNorth() && b.getWest() < b.getEast(), a.getName() + " box is empty/inverted");
                assertTrue(all.contains(b.getSouth(), b.getWest()) && all.contains(b.getNorth(), b.getEast()),
                    a.getName() + " box sticks out of region.bbox");
                assertEquals(RegionConfig.FULL_DETAIL_FILTER, a.getHighwayFilter(), a.getName() + " should be full detail");
            }
        });
    }

    @Test
    void theImportPlanIsABoundedNumberOfRequests() {
        withRegion(cfg -> {
            List<TilePlanner.Tile> tiles = new TilePlanner().plan(cfg);
            long corridor = tiles.stream().filter(t -> t.layer().equals("corridor")).count();
            assertTrue(corridor >= 6 && corridor <= 40, "corridor tiles: " + corridor);
            assertTrue(tiles.size() < 60, "total tiles: " + tiles.size());
            assertEquals(tiles.size(), tiles.stream().map(TilePlanner.Tile::key).distinct().count(), "tile keys must be unique");
        });
    }
}
