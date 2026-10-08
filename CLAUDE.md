# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this project is

A mini Google Maps route planner: users pick start/end points on a real map; the backend runs Dijkstra on an actual OSM road network and returns the route. Built as a portfolio project.

**Region:** Vijayawada – Rajahmundry – Visakhapatnam, Andhra Pradesh, India (bbox: 16.45–17.80 N, 80.55–83.35 E): major roads over the whole bbox (`region.corridor.*`) plus full street-level detail in each city core (`region.areas[*]`). Single-city (Rajahmundry only) setup is documented in a comment in `application.properties`.

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
 RoadNetworkSource   — region.importer.source: overpass (default) | pbf; both return an Overpass-shaped OsmResponse
   OsmDataFetcher      — overpass: HTTP POST, rotates region.importer.urls on failed attempts, deserializes JSON → OsmResponse
   PbfRoadNetworkSource— pbf: reads a local Geofabrik .osm.pbf (osmosis-osm-binary) in two passes per tile; file must be
                         sorted; IMPORT_SOURCE=pbf, IMPORT_PBF_PATH=data/region.osm.pbf
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

### Intent-based routing (`intent/` package)

```
POST /api/intent-route  {query, start?}        — destination NAMED in the text (geocoded)
POST /api/routes/intent {start, end, instruction, unknownDataPolicy?} — coordinates given; no geocoding; parser called with destinationRequired=false after SnapService has validated both points (so bad input costs no LLM call)
IntentRouteService — both endpoints share plan(): PreferenceRoutingService → RouteExplainer (Explanation = text + dataLimitations + notices). route(): IntentParser → PlaceResolver → SnapService. routeBetween(): SnapService → IntentParser → plan; adds map-coverage limitations
RouteIntent — validated schema: Objective (FASTEST/SHORTEST; ECO_FRIENDLY reserved+rejected), Constraints (HARD), Preferences (SOFT). constructor enforces all invariants; place names use a char allowlist
SpringAiIntentParser — Spring AI ChatClient (provider via INTENT_LLM_PROVIDER anthropic|openai|none; keys via env). IntentDraft = LLM-facing schema (reuses Constraints/Preferences; status OK/CLARIFY/REJECT). Strict Jackson mapper; user text is a template PARAM wrapped in <user_request>; QueryRedactor strips coords/emails/phones/links first; failures → LlmErrorClassifier → 429/503/504; CLARIFY → 422. Knows nothing about routing — tested with a mock ChatModel
NominatimPlaceResolver — geocodes names with viewbox+bounded=1 on the region bbox; LLM never emits coordinates
PreferenceRoutingService — hard constraints = pre-filtered adjacency (never relaxed; UnknownDataPolicy STRICT/ALLOW_UNKNOWN decides untagged edges); soft prefs = weight factors / edge-expanded graph for turns, bounded by max-detour-factor vs the plain route; runs the UNMODIFIED DijkstraService; totals recomputed from real edges
```

- Edges carry `EdgeAttrs(roadClass, toll, lit, paved)` (nullable `highway`/`toll`/`lit`/`paved` columns; null = untagged in OSM, not "false"). Existing DBs get the columns via ddl-auto=update (or docker/postgres/migrations/001_*.sql) but need `--force-reimport` to populate them.
- Hard constraints must never be violated or silently relaxed; unknown tags are handled only via the user's `unknownDataPolicy`. Never claim a route is "safe" — report OSM tags only.
- Hot-path caution: minimizeTurns builds an edge-expanded graph per request (~0.7 s worst case on 22k nodes).
- Spring AI 2.0.1 gotchas (all hit and fixed here): needs `spring-ai-retry` added explicitly; its retry default is 10 attempts w/ backoff; with `spring.ai.model.chat` unset BOTH providers configure and OpenAI fails without a key; Jackson 3 rejects a MISSING primitive boolean by default (we disable FAIL_ON_NULL_FOR_PRIMITIVES); vendor SDKs don't fail locally on a blank key (we pre-check); OkHttp timeouts surface as bare InterruptedIOException. SpringAiProviderIntegrationTest exercises all of this against a local fake provider (~13 s).
- Spring Boot 4 has no auto-configured `RestClient.Builder` here (needs the `spring-boot-restclient` module) — use `RestClient.builder()`; `IntentWiringTest` guards this.
- Tests for this feature run without Docker/network (MockRestServiceServer); `IntentRouteService` etc. are not covered by an end-to-end test against a real DB or the real LLM.

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
