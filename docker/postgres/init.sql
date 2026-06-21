-- PostGIS adds geometry types and spatial functions on top of plain PostgreSQL.
-- We use it for one key operation: fast nearest-neighbor lookup (snap click -> graph node).
CREATE EXTENSION IF NOT EXISTS postgis;

-- ─── NODES ────────────────────────────────────────────────────────────────────
-- Each row is a point on the road network (an OSM node that appears in a drivable way).
-- lat/lng are plain doubles for JPA; geom is auto-populated by the trigger below
-- and used exclusively for spatial queries.
CREATE TABLE IF NOT EXISTS nodes (
    id      BIGSERIAL PRIMARY KEY,
    osm_id  BIGINT UNIQUE NOT NULL,
    lat     DOUBLE PRECISION NOT NULL,
    lng     DOUBLE PRECISION NOT NULL,
    geom    GEOMETRY(POINT, 4326)        -- SRID 4326 = WGS-84, the lat/lng standard
);

-- GiST spatial index enables the KNN <-> operator (nearest-neighbor in O(log n))
CREATE INDEX IF NOT EXISTS nodes_geom_idx   ON nodes USING GIST (geom);
CREATE INDEX IF NOT EXISTS nodes_osm_id_idx ON nodes (osm_id);

-- Trigger: whenever a node is inserted or updated, recompute geom from lat/lng.
-- This keeps the JPA entity simple (no Hibernate Spatial needed) while the DB
-- column stays in sync for PostGIS queries.
CREATE OR REPLACE FUNCTION sync_node_geom()
RETURNS TRIGGER AS $$
BEGIN
    NEW.geom = ST_SetSRID(ST_Point(NEW.lng, NEW.lat), 4326);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER node_geom_trigger
BEFORE INSERT OR UPDATE ON nodes
FOR EACH ROW EXECUTE FUNCTION sync_node_geom();

-- ─── EDGES ────────────────────────────────────────────────────────────────────
-- Each row is a directed road segment between two graph nodes.
-- Bidirectional roads produce two rows (A→B and B→A) with the same weight.
-- weight = Haversine distance in metres (can be swapped for travel time later).
CREATE TABLE IF NOT EXISTS edges (
    id           BIGSERIAL PRIMARY KEY,
    from_node_id BIGINT NOT NULL REFERENCES nodes(id),
    to_node_id   BIGINT NOT NULL REFERENCES nodes(id),
    weight       DOUBLE PRECISION NOT NULL,
    osm_way_id   BIGINT           -- original OSM way ID; useful for debugging imports
);

-- Dijkstra always queries "give me all edges leaving node X", so index from_node_id.
CREATE INDEX IF NOT EXISTS edges_from_node_idx ON edges (from_node_id);