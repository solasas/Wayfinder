package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Splits the configured region into the Overpass requests needed to import it.
 *
 * One request for a whole corridor would time out (and could not be resumed), so each layer is cut into a grid of
 * tiles of at most {@code tileSizeDegrees}. Layers, in import order:
 *   1. corridor — the corridor filter over the whole bbox (e.g. major roads between cities), if configured;
 *   2. areas    — each area's own filter over its own box (e.g. street-level detail in a city).
 * With neither configured, the whole bbox is one full-detail layer, exactly as before.
 *
 * Tile keys are stable and include the filter, so a finished tile is recognised on resume, while changing a layer's
 * filter or box produces new keys (and therefore imports the difference).
 */
@Component
public class TilePlanner {

    public record Tile(String layer, String key, RegionConfig.Bbox bbox, String highwayFilter) {
        public String label() { return layer + " " + bbox.toOverpassFormat(); }
    }

    public List<Tile> plan(RegionConfig config) {
        List<Tile> tiles = new ArrayList<>();
        boolean layered = config.getCorridor().isEnabled() || !config.getAreas().isEmpty();

        if (!layered) {
            tiles.addAll(grid("region", config.getBbox(), RegionConfig.FULL_DETAIL_FILTER, 0.1));
            return tiles;
        }
        if (config.getCorridor().isEnabled()) {
            tiles.addAll(grid("corridor", config.getBbox(), config.getCorridor().getHighwayFilter(),
                config.getCorridor().getTileSizeDegrees()));
        }
        for (RegionConfig.Area area : config.getAreas()) {
            tiles.addAll(grid(area.getName(), area.getBbox(), area.getHighwayFilter(), area.getTileSizeDegrees()));
        }
        return tiles;
    }

    static List<Tile> grid(String layer, RegionConfig.Bbox box, String filter, double tileSizeDegrees) {
        if (tileSizeDegrees <= 0) throw new IllegalArgumentException("tile size must be positive for layer '" + layer + "'");
        double height = box.getNorth() - box.getSouth(), width = box.getEast() - box.getWest();
        if (height <= 0 || width <= 0) throw new IllegalArgumentException("layer '" + layer + "' has an empty bounding box");

        int rows = (int) Math.ceil(height / tileSizeDegrees - 1e-9), cols = (int) Math.ceil(width / tileSizeDegrees - 1e-9);
        double dh = height / rows, dw = width / cols;
        List<Tile> out = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                RegionConfig.Bbox b = new RegionConfig.Bbox();
                b.setSouth(box.getSouth() + r * dh);
                b.setNorth(r == rows - 1 ? box.getNorth() : box.getSouth() + (r + 1) * dh);
                b.setWest(box.getWest() + c * dw);
                b.setEast(c == cols - 1 ? box.getEast() : box.getWest() + (c + 1) * dw);
                String key = String.format(Locale.ROOT, "%s|%.5f,%.5f,%.5f,%.5f|%08x",
                    layer, b.getSouth(), b.getWest(), b.getNorth(), b.getEast(), filter.hashCode());
                out.add(new Tile(layer, key, b, filter));
            }
        }
        return out;
    }
}
