# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A mini Google Maps route planner: users pick start/end points on a real map; the backend runs Dijkstra on an actual OSM road network and returns the route. Built as a portfolio project.

**Region:** Rajahmundry urban core, Andhra Pradesh, India (bbox: 16.96–17.01 N, 81.76–81.82 E).

## Commands

```bash
# 1. Start the database (required before anything else)
docker compose up -d

# 2. Import the road network from OpenStreetMap (run once; idempotent)
./mvnw spring-boot:run -Dspring-boot.run.profiles=import

# Re-import (clears existing data first)
./mvnw spring-boot:run -Dspring-boot.run.profiles=import \
       -Dspring-boot.run.arguments=--force-reimport

# 3. Start the API server
./mvnw spring-boot:run

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=ClassName

# Build JAR
./mvnw package -DskipTests
```

## Stack

- **Java 17**, Spring Boot 4.1.0
- **Spring Web MVC** — REST API
- **Spring Data JPA + PostgreSQL + PostGIS** — graph storage and spatial queries
- **Lombok** — `@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor` on entities
- **Docker Compose** — PostgreSQL with PostGIS (image: `postgis/postgis:17-3.5`)
- **React + Leaflet.js** — frontend (not yet built)

## Architecture

### Data pipeline (Phase 1 — complete)

```
Overpass API (OSM)
      │
 OsmDataFetcher      — HTTP POST to overpass-api.de, deserializes JSON → OsmResponse
      │
 OsmParser           — separates nodes/ways, builds Node entities + ParsedEdge records
      │                  (haversine distance as edge weight, handles oneway tags)
 GraphImporter       — CommandLineRunner (@Profile("import")):
      │                  saves nodes (gets DB IDs), translates ParsedEdges → Edge entities, saves edges
      ▼
 PostgreSQL + PostGIS
   nodes(id, osm_id, lat, lng, geom)   ← geom auto-populated by DB trigger
   edges(id, from_node_id, to_node_id, weight, osm_way_id)
```

### Backend API (Phase 2 — complete)

```
GraphService        — @PostConstruct loads all nodes+edges into two maps:
                        adjacency: Map<Long, List<Neighbor>>  (for Dijkstra)
                        nodeIndex: Map<Long, Node>            (for lat/lng lookups)
DijkstraService     — pure algorithm; takes adjacency map as parameter (testable without Spring/DB)
SnapService         — validates coordinates are in-region, then calls NodeRepository.findNearestTo()
RegionController    — GET  /api/region
PathController      — POST /api/shortest-path
GlobalExceptionHandler — maps RouteNotFoundException→404, IllegalArgument→400, IllegalState→503
```

**In-memory vs DB queries for Dijkstra:** Loading the full graph into memory at startup makes each Dijkstra run a pure in-memory operation (< 100 ms). Per-step DB queries would mean hundreds of round-trips per route request — far too slow.

### Frontend (Phase 3 — not yet built)

React + Leaflet.js in a `frontend/` subdirectory. Displays OpenStreetMap tiles, lets users click or search for start/end, calls the backend API, draws the route polyline.

## Key design decisions

- **PostGIS `<->` KNN operator** in `NodeRepository.findNearestTo()`: uses the GiST spatial index for O(log n) nearest-node lookup. Alternative (computing distance for every row) would be O(n).
- **Trigger-populated `geom` column**: the DB trigger `sync_node_geom` keeps the PostGIS geometry column in sync with `lat`/`lng`. Hibernate only maps `lat`/`lng`; the geometry column is invisible to JPA but available for native spatial queries.
- **Directed graph with two edges per bidirectional road**: simpler than storing undirected edges and handling directionality in Dijkstra.
- **`@Profile("import")` on GraphImporter**: prevents accidental graph wipes on every app restart.

## Changing the region

1. Update `region.bbox.*` in `application.properties`
2. Re-run the import with `--force-reimport`
3. Update the frontend map center (when built)
