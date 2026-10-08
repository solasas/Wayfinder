package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** region.importer.source picks exactly one RoadNetworkSource; the default stays Overpass. */
class RoadNetworkSourceSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withBean(RegionConfig.class)
        .withUserConfiguration(OsmDataFetcher.class, PbfRoadNetworkSource.class);

    @Test
    void defaultsToOverpass() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(RoadNetworkSource.class).hasSingleBean(OsmDataFetcher.class));
    }

    @Test
    void overpassCanBeChosenExplicitly() {
        runner.withPropertyValues("region.importer.source=overpass")
            .run(ctx -> assertThat(ctx).hasSingleBean(OsmDataFetcher.class).doesNotHaveBean(PbfRoadNetworkSource.class));
    }

    @Test
    void pbfReplacesOverpass() {
        runner.withPropertyValues("region.importer.source=pbf")
            .run(ctx -> assertThat(ctx).hasSingleBean(PbfRoadNetworkSource.class).doesNotHaveBean(OsmDataFetcher.class));
    }
}
