package com.sashank.map_shortest_path_finder.pipeline;

import com.sashank.map_shortest_path_finder.config.RegionConfig;
import org.openstreetmap.osmosis.osmbinary.BinaryParser;
import org.openstreetmap.osmosis.osmbinary.Osmformat;
import org.openstreetmap.osmosis.osmbinary.file.BlockInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads road data for one tile from a local {@code .osm.pbf} extract (e.g. a Geofabrik download) instead of the
 * public Overpass API. Active with {@code region.importer.source=pbf}; the file is {@code region.importer.pbf-path}.
 *
 * The result has the same shape as an Overpass answer to
 * {@code way["highway"~filter](bbox); out body; >; out skel qt;}: the matching ways that have a node inside the box,
 * plus every node those ways reference (including ones outside the box), so everything downstream is unchanged.
 *
 * A PBF file stores all nodes, then all ways, then all relations (each sorted by id). Per tile that allows:
 *   pass 1  stream the nodes remembering the ids inside the box, then stream the ways keeping the ones that match
 *           the highway filter and touch the box;
 *   pass 2  stream the nodes again to look up the coordinates of every node the kept ways reference, stopping as
 *           soon as the last one is found.
 * Memory is 8 bytes per node inside the box plus the kept ways. Each tile costs a read of the file, so for a large
 * region clip the download to the region first (osmium extract -b W,S,E,N) — the README shows how.
 *
 * Differences from Overpass: a way that crosses the box without any node inside it is not selected (Overpass
 * includes it); ways that start outside and enter through a border are still selected as soon as one node is inside.
 */
@Service
@ConditionalOnProperty(name = "region.importer.source", havingValue = "pbf")
public class PbfRoadNetworkSource implements RoadNetworkSource {

    private static final Logger log = LoggerFactory.getLogger(PbfRoadNetworkSource.class);

    private final Path pbf;

    @Autowired
    public PbfRoadNetworkSource(RegionConfig regionConfig) {
        this(Path.of(regionConfig.getImporter().getPbfPath()));
    }

    PbfRoadNetworkSource(Path pbf) {
        this.pbf = pbf;
    }

    @Override
    public OsmResponse fetchRoadNetwork(RegionConfig.Bbox bbox, String highwayFilter) {
        if (!Files.isRegularFile(pbf)) {
            throw new IllegalStateException("PBF extract not found: " + pbf.toAbsolutePath() + ". Download one (for India: "
                + "https://download.geofabrik.de/asia/india.html), optionally clip it with osmium, and set "
                + "region.importer.pbf-path.");
        }
        long started = System.nanoTime();
        Pattern filter = Pattern.compile(highwayFilter);

        WayScan ways = new WayScan(bbox, filter);
        read(ways);
        long[] needed = ways.neededNodeIds();

        NodeScan coords = new NodeScan(needed);
        if (needed.length > 0) {
            read(coords);
        }

        List<OsmResponse.OsmElement> elements = new ArrayList<>(needed.length + ways.kept.size());
        int missing = 0;
        for (int i = 0; i < needed.length; i++) {
            if (Double.isNaN(coords.lat[i])) { missing++; continue; } // referenced by a way but absent from the extract
            OsmResponse.OsmElement n = new OsmResponse.OsmElement();
            n.setType("node");
            n.setId(needed[i]);
            n.setLat(coords.lat[i]);
            n.setLon(coords.lon[i]);
            elements.add(n);
        }
        for (KeptWay w : ways.kept) {
            OsmResponse.OsmElement e = new OsmResponse.OsmElement();
            e.setType("way");
            e.setId(w.id());
            e.setNodes(toList(w.refs()));
            e.setTags(w.tags());
            elements.add(e);
        }

        log.info("PBF {}: {} ways and {} nodes for {} in {} ms{}.", pbf.getFileName(), ways.kept.size(),
            needed.length - missing, bbox.toOverpassFormat(), (System.nanoTime() - started) / 1_000_000,
            missing > 0 ? " (" + missing + " referenced nodes lie outside the extract)" : "");

        OsmResponse response = new OsmResponse();
        response.setElements(elements);
        return response;
    }

    private void read(BinaryParser parser) {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(pbf), 1 << 16)) {
            new BlockInputStream(in, parser).process();
        } catch (StopReading done) {
            // the scan has everything it needs; the rest of the file is irrelevant
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read PBF extract " + pbf + ": " + e.getMessage(), e);
        }
    }

    private static List<Long> toList(long[] refs) {
        List<Long> list = new ArrayList<>(refs.length);
        for (long r : refs) list.add(r);
        return list;
    }

    // ── scans ────────────────────────────────────────────────────────────────

    private record KeptWay(long id, long[] refs, Map<String, String> tags) {}

    /** Thrown by a scan to end the read early; carries no stack trace. */
    private static final class StopReading extends RuntimeException {
        StopReading() { super(null, null, false, false); }
    }

    /** Callbacks every scan shares; the PBF block decoding lives here once. */
    private abstract static class Scan extends BinaryParser {
        void onNode(long id, double lat, double lon) {}
        void onWays(List<Osmformat.Way> ways) {}

        @Override protected void parseRelations(List<Osmformat.Relation> relations) {}
        @Override protected void parse(Osmformat.HeaderBlock header) {}
        @Override public void complete() {}

        @Override
        protected void parseNodes(List<Osmformat.Node> nodes) {
            for (Osmformat.Node n : nodes) onNode(n.getId(), parseLat(n.getLat()), parseLon(n.getLon()));
        }

        @Override
        protected void parseDense(Osmformat.DenseNodes dense) {
            long id = 0, lat = 0, lon = 0; // all three are delta-coded
            for (int i = 0; i < dense.getIdCount(); i++) {
                id += dense.getId(i);
                lat += dense.getLat(i);
                lon += dense.getLon(i);
                onNode(id, parseLat(lat), parseLon(lon));
            }
        }

        @Override
        protected void parseWays(List<Osmformat.Way> ways) {
            if (!ways.isEmpty()) onWays(ways); // called for every group, usually with an empty list
        }

        /** Value of the way's tag {@code key}, or null. */
        String tag(Osmformat.Way way, String key) {
            for (int i = 0; i < way.getKeysCount(); i++) {
                if (key.equals(getStringById(way.getKeys(i)))) return getStringById(way.getVals(i));
            }
            return null;
        }

        static long[] refs(Osmformat.Way way) {
            long[] refs = new long[way.getRefsCount()];
            long ref = 0; // delta-coded
            for (int i = 0; i < refs.length; i++) {
                ref += way.getRefs(i);
                refs[i] = ref;
            }
            return refs;
        }
    }

    /** Pass 1: ids of the nodes inside the box, then the highway ways that touch it. */
    private static final class WayScan extends Scan {
        private final RegionConfig.Bbox bbox;
        private final Pattern filter;
        private LongSet inBox = new LongSet();
        private boolean waysStarted;
        final List<KeptWay> kept = new ArrayList<>();

        WayScan(RegionConfig.Bbox bbox, Pattern filter) {
            this.bbox = bbox;
            this.filter = filter;
        }

        @Override
        void onNode(long id, double lat, double lon) {
            if (waysStarted) {
                throw new IllegalStateException("The PBF file lists a node after the first way. It must be sorted "
                    + "(nodes, then ways, then relations): run  osmium sort in.osm.pbf -o out.osm.pbf  on it.");
            }
            if (bbox.contains(lat, lon)) inBox.add(id);
        }

        @Override
        void onWays(List<Osmformat.Way> ways) {
            if (!waysStarted) {
                waysStarted = true;
                inBox.seal();
            }
            for (Osmformat.Way way : ways) {
                String highway = tag(way, "highway");
                // Overpass's  ~  is an unanchored regex search; the project's filters anchor themselves with ^...$
                if (highway == null || !filter.matcher(highway).find()) continue;
                long[] refs = refs(way);
                if (!touches(refs)) continue;
                Map<String, String> tags = new HashMap<>();
                for (int i = 0; i < way.getKeysCount(); i++) {
                    tags.put(getStringById(way.getKeys(i)), getStringById(way.getVals(i)));
                }
                kept.add(new KeptWay(way.getId(), refs, tags));
            }
        }

        private boolean touches(long[] refs) {
            for (long r : refs) if (inBox.contains(r)) return true;
            return false;
        }

        /** Sorted, de-duplicated ids of every node the kept ways reference. */
        long[] neededNodeIds() {
            inBox = null; // no longer needed; let it go before pass 2
            LongSet all = new LongSet();
            for (KeptWay w : kept) for (long r : w.refs()) all.add(r);
            return all.sortedDistinct();
        }
    }

    /** Pass 2: coordinates of the needed nodes; ends the read once all are found or the nodes run out. */
    private static final class NodeScan extends Scan {
        private final long[] needed;
        final double[] lat;
        final double[] lon;
        private int found;

        NodeScan(long[] needed) {
            this.needed = needed;
            this.lat = new double[needed.length];
            this.lon = new double[needed.length];
            Arrays.fill(lat, Double.NaN);
            Arrays.fill(lon, Double.NaN);
        }

        @Override
        void onNode(long id, double nodeLat, double nodeLon) {
            int i = Arrays.binarySearch(needed, id);
            if (i < 0) return;
            if (Double.isNaN(lat[i])) found++;
            lat[i] = nodeLat;
            lon[i] = nodeLon;
        }

        @Override
        void onWays(List<Osmformat.Way> ways) {
            throw new StopReading(); // nodes come first in the file: they are all behind us
        }

        @Override
        public void handleBlock(org.openstreetmap.osmosis.osmbinary.file.FileBlock block) {
            super.handleBlock(block);
            if (found == needed.length) throw new StopReading();
        }
    }

    /** Growable array of primitive longs; sorted once before lookups (PBF node ids arrive ascending anyway). */
    private static final class LongSet {
        private long[] a = new long[1024];
        private int n;
        private boolean sorted = true;

        void add(long v) {
            if (n == a.length) a = Arrays.copyOf(a, n * 2);
            if (n > 0 && v < a[n - 1]) sorted = false;
            a[n++] = v;
        }

        void seal() {
            if (!sorted) {
                Arrays.sort(a, 0, n);
                sorted = true;
            }
        }

        boolean contains(long v) {
            return Arrays.binarySearch(a, 0, n, v) >= 0;
        }

        long[] sortedDistinct() {
            Arrays.sort(a, 0, n);
            int m = 0;
            for (int i = 0; i < n; i++) {
                if (m == 0 || a[i] != a[m - 1]) a[m++] = a[i];
            }
            return Arrays.copyOf(a, m);
        }
    }
}
