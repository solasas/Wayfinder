# Map Shortest Path Finder

A mini Google Maps route planner. Pick two points, and the backend finds a real driving route between them using an actual road network pulled from OpenStreetMap — not a straight line, but the roads a car could actually take.

It's built around a custom **Dijkstra's algorithm** implementation (plus two variants of it — see below), and answers three different questions:

- **"What's the best route from A to B?"** — optimizing for shortest distance or fastest time
- **"What are a few good alternative routes?"** — genuinely distinct roads, not the same route with a detour
- **"How far can I get in N minutes?"** — an isochrone: every road reachable within a travel budget

> **Region:** Rajahmundry urban core, Andhra Pradesh, India

---

## Features

| Feature | Endpoint | Powered by |
|---|---|---|
| Shortest/fastest route between two points | `POST /api/shortest-path` | `DijkstraService` (single-direction or bidirectional) |
| A few distinct alternative routes | `POST /api/shortest-path?alternates=true` | `AlternateRoutesService` (Yen's k-shortest-paths) |
| "Everywhere reachable in N minutes" | `GET /api/isochrone` | `DijkstraService` (cutoff traversal, no target) |
| Region metadata (bbox, center) | `GET /api/region` | `RegionConfig` |
| Real road network from OpenStreetMap | one-time import | `OsmDataFetcher` → `OsmParser` → `GraphImporter` |
| Distance- **and** speed-aware routing | — | each road segment carries a typical speed from its OSM `highway` tag |
| Automated tests, including against a real DB | `mvn test` | JUnit 5 + Mockito (pure unit) + Testcontainers (integration) |
| CI on every push/PR | GitHub Actions | `.github/workflows/ci.yml` |

---

## What This Project Demonstrates

| Concept | Where it appears |
|---|---|
| Graph algorithms — Dijkstra, bidirectional Dijkstra, Yen's k-shortest-paths | `DijkstraService.java`, `AlternateRoutesService.java` |
| Geospatial data (PostGIS extension, GiST spatial index) | `NodeRepository.java`, `init.sql` |
| Real-world data pipeline (OpenStreetMap) | `OsmDataFetcher`, `OsmParser`, `GraphImporter` |
| REST API design (Spring Boot) | `PathController`, `IsochroneController`, `RegionController` |
| In-memory vs DB query tradeoffs | `GraphService.java` |
| Integration testing against real infrastructure | `AbstractIntegrationTest`, `SnapServiceIntegrationTest`, `GraphServiceIntegrationTest` (Testcontainers) |
| Unit testing pure algorithms without any infrastructure | `DijkstraServiceTest.java`, `AlternateRoutesServiceTest.java` |
| CI automation | `.github/workflows/ci.yml` |

---

## Tech Stack

| Layer | Technology | Why |
|---|---|---|
| Backend | Java 17 + Spring Boot 4 | REST API + dependency injection |
| Database | PostgreSQL + PostGIS | Stores the road graph; PostGIS extension installed for spatial queries |
| Map data | OpenStreetMap via Overpass API | Free, real-world road network data |
| Algorithms | Dijkstra (single-direction + bidirectional), Yen's k-shortest-paths | Optimal shortest path, faster large-graph search, and distinct alternate routes |
| Containers | Docker + Docker Compose | One command to spin up the dev database |
| Testing | JUnit 5, Mockito, Testcontainers | Fast mocked unit tests + real-Postgres integration tests, no shared test DB needed |
| CI | GitHub Actions | Runs the full test suite (including Testcontainers) on every push/PR to `main` |
| Frontend *(planned)* | React + Leaflet.js | Interactive map with OpenStreetMap tiles |

---

## How It Works — End to End

```
User clicks two points on the map, picks "fastest" or "shortest"
          │
          ▼
POST /api/shortest-path  { start, end, optimize: "time" | "distance" }
          │
          ├─ SnapService ──────► nearest-node query ──► nearest graph node to each click
          │
          ├─ DijkstraService ──► runs on in-memory adjacency map, minimising
          │                       whichever weight was requested ──► ordered node IDs
          │
          └─ PathController ──► converts node IDs → lat/lng coordinates
          │
          ▼
Response: { path: [{lat,lng}, ...], distanceMeters: 3456, estimatedTimeSecs: 315 }
          │
          ▼
Frontend draws the polyline on the Leaflet map
```

Add `?alternates=true&k=3` to the same request and `PathController` calls `AlternateRoutesService` instead, returning a JSON array of up to 3 distinct routes rather than one. Call `GET /api/isochrone` instead and `IsochroneController` runs a different traversal — no target node, just "how far can I get" — and returns every node reached within budget. Both reuse the exact same in-memory graph as normal routing; see [REST API](#rest-api) below for full examples.

---

## Architecture

### Data Pipeline (runs once to populate the database)

```
Overpass API (OpenStreetMap)
        │  HTTP POST with Overpass QL query
        ▼
OsmDataFetcher.java
  — Fetches all drivable roads in the Rajahmundry bounding box
  — Returns raw JSON: a flat list of nodes (points) and ways (roads)

        │
        ▼
OsmParser.java
  — Separates nodes (lat/lng points) and ways (ordered lists of node IDs)
  — Builds graph edges: for each consecutive pair of nodes on a road → one directed edge
  — Calculates edge distance using the Haversine formula (real-world distance in metres)
  — Looks up a typical speed for the road from its OSM `highway` tag
      (motorway 80 km/h, primary 60, secondary 50, residential 30, service 15, default 40)
  — Handles one-way roads (oneway=yes tag → only forward edge created)

        │
        ▼
GraphImporter.java  (@Profile("import") — only runs when explicitly triggered)
  — Saves nodes to PostgreSQL (batch of 500 at a time)
  — Saves edges to PostgreSQL, each with its distance and speed
  — Logs node/edge counts as a sanity check
```

### Database Schema

```sql
nodes (id, osm_id, lat, lng, geom)
  — geom is a PostGIS Point column, auto-populated by a DB trigger from lat/lng
  — GiST spatial index on geom, ready for fast nearest-neighbour queries
    (see the honest note on NodeRepository below — this index isn't wired up yet)

edges (id, from_node_id, to_node_id, distance_meters, speed_kmh, osm_way_id)
  — distance_meters = Haversine distance
  — speed_kmh = typical speed for the road type (from the OSM highway tag)
  — Bidirectional roads produce two rows: A→B and B→A
  — Index on from_node_id (Dijkstra always queries "all roads leaving node X")
```

### Backend Services

```
GraphService
  — Loads the full graph from PostgreSQL into memory once at startup (@PostConstruct)
  — Builds two maps:
      adjacency : Map<Long, List<Neighbor>>   (for Dijkstra traversal)
      nodeIndex : Map<Long, Node>             (for lat/lng lookups)
  — Each Neighbor carries both distanceMeters and a timeSeconds derived from
    distanceMeters ÷ speedKmh, so both routing modes are ready without touching the DB again
  — Deliberately "sticky": if loadGraph() ever finds zero nodes, it logs a warning and
    keeps serving whatever graph it already had, rather than going empty
  — Why in-memory? Dijkstra would need hundreds of DB queries per route otherwise.
    A city-scale graph (~9k nodes, ~21k edges here) fits in a few MB of heap.

DijkstraService
  — Pure algorithm class with zero Spring/DB dependencies — takes the adjacency map as
    a parameter, so it's fully unit-testable with any hand-built graph
  — findShortestPath — standard single-direction Dijkstra: min-heap, lazy deletion for
    stale heap entries, stops as soon as the target is popped
  — findShortestPathBidirectional — grows a second search backwards from the target at
    the same time, over a reverse adjacency map (buildReverseAdjacency), alternating one
    expansion per side; terminates once neither frontier can beat the best meeting point
    found so far. Meets in the middle instead of reading all the way out to the target
    from one side alone.
  — findReachableNodes — single-source traversal with a cost cutoff and no target node;
    powers the isochrone endpoint ("everywhere reachable in N minutes")
  — Every PathResult carries nodesExpanded (how many nodes were actually popped and
    relaxed), so the single-direction and bidirectional strategies can be compared
    directly on the same query
  — WeightType (DISTANCE or TIME) tells any of the above which of a Neighbor's two
    weights to minimise — same algorithms, different cost function

AlternateRoutesService
  — Yen's k-shortest-paths algorithm, built on top of DijkstraService rather than
    reimplementing pathfinding from scratch
  — To find the next path: for every "spur node" along the best path found so far, prune
    a scratch copy of the adjacency map (remove the edge any already-accepted path takes
    out of that spur node if it shares the same root, and remove the earlier root-path
    nodes entirely so the spur search can't loop back through them), rerun Dijkstra from
    there, and splice root + spur into a candidate
  — The candidate pool persists across rounds — a candidate found but not chosen this
    round is still eligible next round, which is what makes this genuinely Yen's
    algorithm rather than a greedy per-round approximation
  — Returns up to k distinct paths, cheapest first (fewer if the graph doesn't have k
    genuinely distinct routes)

SnapService
  — "Snap click to nearest road node"
  — Validates that the click is inside the supported region
  — Calls NodeRepository.findNearestTo() — see the honest note below on what that
    query actually does today
```

> **Honest note on `NodeRepository.findNearestTo()`:** it currently ranks candidates by
> a plain Euclidean `(lat-:lat)² + (lng-:lng)²` `ORDER BY ... LIMIT 1`, not PostGIS's
> `<->` KNN operator over the `geom` column the schema and GiST index above are set up
> for. It returns the correct nearest node either way — the difference is only
> performance (O(n) today vs. O(log n) with the index) — but the PostGIS wiring
> described by the schema isn't actually in the query path yet.

### REST API

| Endpoint | Method | Description |
|---|---|---|
| `/api/region` | GET | Returns the supported area name, center point, and bounding box. Frontend uses this to initialize the map. |
| `/api/shortest-path` | POST | Best route between two points — or, with `?alternates=true`, a list of distinct alternates. |
| `/api/isochrone` | GET | Every point reachable from a location within a travel-time (or distance) budget. |
| `/api/routes/intent` | POST | Coordinates + a free-text instruction ("fastest route without tolls"): routes between the two points under the structured constraints/preferences extracted from the text. |
| `/api/intent-route` | POST | Natural-language routing where the *destination is named in the text* ("fastest way to the railway station…"); needs the geocoder. |

#### `POST /api/shortest-path`

```json
{
  "start": { "lat": 16.975, "lng": 81.778 },
  "end":   { "lat": 16.990, "lng": 81.800 },
  "optimize": "time"
}
```
`optimize` is `"time"` or `"distance"`, defaulting to `"time"` if omitted — what most map users expect.

Response (single route, the default):
```json
{
  "path": [
    { "lat": 16.975, "lng": 81.778 },
    { "lat": 16.976, "lng": 81.780 },
    { "lat": 16.990, "lng": 81.800 }
  ],
  "distanceMeters": 3156.4,
  "estimatedTimeSecs": 315
}
```
`distanceMeters` and `estimatedTimeSecs` are always both returned — the actual totals for whichever route was found, regardless of which one was optimized for.

**Alternate routes:** add `?alternates=true&k=3` (k defaults to 3) and the response becomes a JSON array of up to `k` route objects, same shape as above, sorted cheapest-first by whichever `optimize` mode was requested:
```json
[
  { "path": [ ... ], "distanceMeters": 3156.4, "estimatedTimeSecs": 315 },
  { "path": [ ... ], "distanceMeters": 3298.1, "estimatedTimeSecs": 322 },
  { "path": [ ... ], "distanceMeters": 3540.0, "estimatedTimeSecs": 340 }
]
```

#### `GET /api/isochrone?lat=&lng=&minutes=&optimize=`

```
GET /api/isochrone?lat=16.985&lng=81.79&minutes=5&optimize=time
```
`optimize` defaults to `"time"`. For `optimize=distance`, the `minutes` budget is converted to a distance cutoff using a nominal 40 km/h — there's no separate distance parameter, since "how far in N minutes" is the natural way to ask for an isochrone either way.

```json
{
  "center": { "lat": 16.9855292, "lng": 81.7890337 },
  "minutes": 5,
  "reachableNodes": [
    { "lat": 17.0001239, "lng": 81.7776129, "distance": 272.02 },
    { "lat": 16.9857123, "lng": 81.801024,  "distance": 288.47 }
  ]
}
```
`center` is the graph node the query snapped to, not necessarily the exact requested point. Each `reachableNodes[i].distance` is the cumulative cost to reach that node in the units of `optimize` — seconds for `"time"`, metres for `"distance"`.

#### `POST /api/routes/intent`

Start and end are **coordinates**, so no geocoding is involved (and no place name is ever assumed to resolve); the instruction only says *how* to travel. Both points go through the existing `SnapService` / `RegionConfig` — outside Rajahmundry → 400, checked *before* the LLM is called — and are snapped to the nearest graph node.

```json
POST /api/routes/intent
{ "start": { "lat": 16.975, "lng": 81.778 },
  "end":   { "lat": 16.990, "lng": 81.800 },
  "instruction": "Find the fastest route without tolls" }
```
Optional fields:
- `"unknownDataPolicy": "STRICT" | "ALLOW_UNKNOWN"` — see below; default `STRICT`.
- `"objective": "FASTEST" | "SHORTEST" | "AUTO"` — an explicit choice (e.g. from a UI selector) **overrides** what the instruction implies; omitted or `AUTO` lets the instruction decide (default fastest). `ECO_FRIENDLY` → 400.

The response (field names for `path`, `distanceMeters`, `estimatedTimeSecs` match `/api/shortest-path`; every value comes from the graph engine, so only the *shape* is shown):

```
{ "path": [ {lat,lng}, ... ],                // ordered route coordinates
  "distanceMeters": …, "estimatedTimeSecs": …,   // true totals of the chosen road segments
  "objective": "FASTEST" | "SHORTEST",
  "constraints": { "avoidTolls", "avoidHighways", "avoidUnpaved" },        // HARD rules; the route satisfies all of them
  "preferences": { "preferWellLit", "minimizeTurns" },                      // SOFT wishes requested
  "preferencesApplied": { … },                                              // which of those actually shaped the route
  "unknownDataPolicy": "STRICT",
  "snappedStart": {lat,lng}, "snappedEnd": {lat,lng},                       // where the path really begins/ends
  "explanation": "Fastest route: 2.3 km, about 4 min. Every segment is tagged non-toll …",
  "dataLimitations": [ "Toll status is tagged on under 1% of road segments in the map data.", … ],
  "notices": [ "Not supported, so ignored: avoid traffic", … ],
  "details": { tollUnverifiedMeters, surfaceUnverifiedMeters, highwayClassUnknownMeters, litMeters, unlitMeters, unknownLitMeters, turns },
  "plainRoute": { distanceMeters, timeSeconds, turns } | null }           // same constraints, no soft preferences
```
- **`dataLimitations`** = what the map data can't establish: how sparse the toll / surface / lighting tags are across the imported graph (shown below 95% coverage), unverified portions of the route, and — under `STRICT` — that a shorter compliant route through untagged segments may exist. **`notices`** = everything else: unsupported requests that were ignored, a place name in the instruction that was ignored in favour of your coordinates, a soft preference dropped because it would have cost more than the allowed margin, start and end snapping to the same point.
- A constraint failure is a 404 whose body also carries `"code": "CONSTRAINTS_NOT_SATISFIABLE"` and `"retryWithAllowUnknown": true|false` (true = the constraints were merely *unverifiable* under `STRICT`, so resending with `ALLOW_UNKNOWN` can work), so a client never has to parse the message.
- `preferencesApplied.preferWellLit` is true only when the route actually contains lighting-tagged segments; a requested preference that had nothing to act on is reported as not applied.
- Errors reuse the project's conventions: bad/missing fields or out-of-region points → 400; no route (or none that satisfies the constraints / can be verified) → 404; ambiguous or contradictory instruction → 422 with a `question`; LLM rate limit → 429, timeout → 504, not configured/unavailable → 503.

#### `POST /api/intent-route`

Describe the trip in plain English. An LLM (via **Spring AI**, provider selectable by configuration) turns the text into a fixed structure; the destination name is geocoded with Nominatim **restricted to the region's bounding box**; the existing Dijkstra engine computes the route. The model never produces coordinates, code, SQL or graph operations — it has no tools and the only thing it can fill in is the schema below.

**LLM configuration** (environment variables; the app starts fine without them and this endpoint answers 503):

| Variable | Meaning |
|---|---|
| `INTENT_LLM_PROVIDER` | `anthropic` (default), `openai`, or `none` |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` | credentials for the chosen provider |
| `INTENT_LLM_MODEL` | model name override (defaults: `claude-haiku-4-5-20251001` / `gpt-4o-mini`) |

Setting `INTENT_LLM_PROVIDER=openai` without `OPENAI_API_KEY` fails at startup (Spring AI validates it). With the Anthropic provider and no key, the endpoint reports "not configured" **without sending anything** to the provider.

**How the parsing service is kept safe** (`SpringAiIntentParser`, independent of routing so it is tested with canned model replies):
- *Strict structured output:* the JSON schema is generated from `IntentDraft` and sent with the prompt; the reply is parsed by a strict mapper — unknown fields, wrong types (`"yes"` for a boolean, `42` for a name) and invalid enum values are rejected. An omitted flag means `false`.
- *Untrusted input:* the user's text is passed as a template **parameter** inside `<user_request>` delimiters (so `{braces}` or a fake closing tag can't alter the prompt), and the system prompt tells the model to treat it as data.
- *Server-side validation of everything:* `RouteIntent`'s constructor enforces the invariants; place names are restricted to letters/digits/basic punctuation, so SQL/markup/shell-looking strings are rejected before they reach the geocoder.
- *Ambiguity and contradictions:* the model reports `status: CLARIFY` for a vague destination ("the station") or contradictory instructions ("fastest and shortest"); the API answers **422** with `{"clarificationNeeded": true, "question": "..."}`. A request that isn't about travel is a 400 with a fixed message (the model's text is never echoed). Not caught: a *specific* name that matches several places — the geocoder takes the top hit inside the region.
- *Privacy:* coordinates, links, e-mail addresses and phone numbers are stripped from the text before it leaves the server, and the structured `start` coordinates are never sent. Place names necessarily are. Raw queries are not logged.
- *Provider failures* are classified without leaking provider detail: rate limit → **429**, timeout → **504**, bad/missing credentials or other errors → **503**. Waiting is bounded (`spring.ai.*.timeout=15s`, 1 SDK retry, Spring AI retry capped at 2 attempts — its default is 10 attempts with exponential backoff).

After upgrading, run the import with `--force-reimport` so edges get their road-class / toll / lit / surface attributes; before that, only the objective and destination are honoured and the hard constraints can't be verified. `avoidUnpaved` relies on OSM `surface` tags, which are sparse, and the import filter excludes `track` roads.

The model's output must become this validated structure (`RouteIntent`); anything else is rejected with 400:

```json
{ "destination": "railway station", "objective": "FASTEST",
  "constraints": { "avoidTolls": true, "avoidHighways": true, "avoidUnpaved": false },
  "preferences": { "preferWellLit": true, "minimizeTurns": false } }
```

- **`objective`** — `FASTEST` (minimise travel time) or `SHORTEST` (minimise distance); these map directly onto the existing `WeightType`. `ECO_FRIENDLY` is reserved but rejected: it needs a documented emissions model and per-segment data (vehicle, gradient) that don't exist here.
- **Hard `constraints`** (`avoidTolls`, `avoidHighways`, `avoidUnpaved`) — matching edges are removed from the graph before any search, and constraints are **never relaxed**: a returned route always satisfies them, otherwise you get a 404 saying why. Which edges qualify depends on the request's **`unknownDataPolicy`** (below).
- **Highway policy** — `avoidHighways` excludes the road classes in `intent.routing.highway-classes` (default `MOTORWAY, TRUNK`, including `_link` ramps). `PRIMARY` is *not* excluded by default even though some Indian national highways are tagged primary in OSM; add it to the list if that's what you mean.
- **Soft `preferences`** — reweight edges, never forbid them:
  - `preferWellLit`: each edge's cost is multiplied by a unitless factor (lit ×1.0, untagged ×1.25, unlit ×1.5 — configurable), so costs stay in the objective's units.
  - `minimizeTurns`: a turn depends on the previous edge, so the search runs over an **edge-expanded graph** (one state per directed edge; each transition is priced by the turn angle). A bend of ≥30° costs 6 s (FASTEST) or the equivalent 67 m at 40 km/h (SHORTEST). Same `DijkstraService`, different graph. Costs ~0.7 s for a corner-to-corner route on a 22k-node graph (plain routing ~25 ms), so it is only used when requested.
  - **Bounded:** a preference route is accepted only if its real cost is within `intent.routing.max-detour-factor` (default 1.3) of the plain route under the same constraints; otherwise the plain route is returned and flagged. The response's `plainRoute` shows the comparison.
- No safety claims are made. Lighting is reported only as what OSM tags say ("X% tagged lit, Y% unlit, Z% untagged").
- Unknown fields at any level, wrong types, and oversized names are rejected, so a misspelt `avoidToll` can't silently drop a constraint. Requests no field covers (e.g. "avoid traffic") go to `warnings`.
- To add a constraint/preference: add a component to `Constraints`/`Preferences`, then the validator, `PreferenceRoutingService` and `RouteExplainer` (a test fails if the validator is missed).

**Missing road data — `unknownDataPolicy`** (request field, set by the user; the language model never sets it):

| Policy | An edge is usable if… | Result |
|---|---|---|
| `STRICT` (default) | its tags *positively* show it complies (`toll=no`, a paved `surface`, a known road class outside the highway policy) | Any returned route provably satisfies every hard constraint. But OSM `toll`/`surface` tags are sparse, so there may be none → 404 `CONSTRAINTS_UNVERIFIABLE`-style message telling you to resend with `ALLOW_UNKNOWN`. |
| `ALLOW_UNKNOWN` | it is not *known* to violate | Route found if one exists; `details` and `warnings` report how many metres have unknown toll/surface/class, i.e. where compliance is unverified. Known violations are still excluded. |

Failure modes (all 404, distinct messages): no road connection at all · constraints can't be verified under `STRICT` (but could be with `ALLOW_UNKNOWN`) · no route satisfies the constraints even when unknown is accepted.

**Schema migration:** new nullable `edges` columns (`highway`, `toll`, `lit`, `paved`) are added automatically at app start by `ddl-auto=update`; `docker/postgres/migrations/001_edge_road_attributes.sql` is an idempotent script for running with `ddl-auto=validate`. Existing rows stay `NULL` (unknown) until `--force-reimport`; startup logs the attribute coverage and warns if it is zero.

Route totals (`distanceMeters`, `estimatedTimeSecs`) are always the real figures, not the penalised costs.

The response contains `intent` (what was understood), `origin`, `destination`, `route` (same shape as `/api/shortest-path`), `explanation` and `warnings`. The explanation is generated from the route's measured segments, not by the model, so it can't claim something the route doesn't do. OSM `lit` and `toll` tags are sparse; when coverage is low the response says so instead of implying a guarantee.

Extra error cases: unknown/out-of-region destination → 404; unintelligible request or missing start → 400; ambiguous/contradictory request → 422; LLM rate limit → 429; LLM timeout → 504; missing API key, LLM or geocoder outage → 503.

**Error responses** (all three endpoints, where applicable):

| Situation | HTTP Status |
|---|---|
| Coordinates outside Rajahmundry | 400 Bad Request |
| Invalid `optimize` value | 400 Bad Request |
| `k` or `minutes` not positive | 400 Bad Request |
| No road connection between the two points | 404 Not Found |
| Import hasn't been run yet (empty graph) | 503 Service Unavailable |

---

## Project Structure

```
map_shortest_path_finder/
├── .github/workflows/ci.yml             # GitHub Actions: mvn test on push/PR to main
├── docker-compose.yml                   # PostgreSQL + PostGIS container (host port 5433)
├── docker/postgres/init.sql             # Schema: nodes, edges, spatial index, trigger
│
└── src/
    ├── main/java/com/sashank/.../
    │   ├── config/
    │   │   ├── RegionConfig.java            # Bounding box loaded from application.properties
    │   │   └── CorsConfig.java              # Allows React frontend (localhost:3000/5173) to call the API
    │   │
    │   ├── model/
    │   │   ├── Node.java                    # JPA entity: graph vertex (osm_id, lat, lng)
    │   │   └── Edge.java                    # JPA entity: directed road segment (from, to, distance, speed)
    │   │
    │   ├── repository/
    │   │   ├── NodeRepository.java          # Nearest-node query (see honest note above)
    │   │   └── EdgeRepository.java          # findByFromNodeId for Dijkstra adjacency lookups
    │   │
    │   ├── pipeline/                        # Data import — runs once, not part of normal startup
    │   │   ├── OsmDataFetcher.java          # Calls Overpass API
    │   │   ├── OsmResponse.java             # Jackson mapping for the Overpass API JSON response
    │   │   ├── OsmParser.java               # Converts raw OSM JSON → graph nodes + edges + speeds
    │   │   └── GraphImporter.java           # CommandLineRunner (@Profile("import"))
    │   │
    │   ├── service/
    │   │   ├── GraphService.java            # Loads graph into memory at startup
    │   │   ├── DijkstraService.java         # Shortest path: single-direction, bidirectional, cutoff traversal
    │   │   ├── AlternateRoutesService.java  # Yen's k-shortest-paths
    │   │   └── SnapService.java             # Nearest node lookup
    │   │
    │   ├── controller/
    │   │   ├── RegionController.java        # GET /api/region
    │   │   ├── PathController.java          # POST /api/shortest-path (+ ?alternates)
    │   │   └── IsochroneController.java     # GET /api/isochrone
    │   │
    │   ├── dto/                             # Request/response data shapes
    │   │   ├── LatLng.java
    │   │   ├── PathRequest.java
    │   │   ├── PathResponse.java
    │   │   ├── IsochroneResponse.java
    │   │   ├── ReachableNodeDto.java
    │   │   └── RegionResponse.java
    │   │
    │   └── exception/
    │       ├── RouteNotFoundException.java
    │       └── GlobalExceptionHandler.java  # Maps exceptions → HTTP status codes
    │
    └── test/java/com/sashank/.../
        ├── service/                          # Pure unit tests — no Spring, no DB
        │   ├── DijkstraServiceTest.java
        │   ├── AlternateRoutesServiceTest.java
        │   └── SnapServiceTest.java          # Mockito-mocked repository
        │
        └── integration/                      # Real PostgreSQL + PostGIS via Testcontainers
            ├── AbstractIntegrationTest.java   # @Testcontainers base class, singleton container
            ├── SnapServiceIntegrationTest.java
            └── GraphServiceIntegrationTest.java
```

---

## Running the Project

### Prerequisites
- Java 17+
- Maven (or use the included `./mvnw` wrapper)
- Docker (also needed to run the test suite — see below)

### Step 1 — Start the database
```bash
docker compose up -d
```
This starts PostgreSQL with PostGIS on `localhost:5433` (not the default 5432 — see note below). On first run, Docker automatically executes `init.sql` which creates the `nodes` and `edges` tables and the spatial index.

> **Notes if things don't come up cleanly:**
> - **Port 5433, not 5432:** `docker-compose.yml` maps the container to host port `5433` to avoid clashing with a native/Homebrew PostgreSQL install that might already own 5432 on your machine. `application.properties` already points at `5433` — no change needed unless you edit the compose file.
> - **Apple Silicon (M-series Mac):** the `postgis/postgis:17-3.5` image only publishes an `amd64` build, so `docker-compose.yml` pins `platform: linux/amd64` and Docker runs it emulated. This works fine, just a touch slower to pull the first time.

### Step 2 — Import road network data
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=import
```
Fetches Rajahmundry road data from OpenStreetMap's Overpass API and stores it in the database. Takes ~30–60 seconds. Only needs to run once.

To re-import (clears existing data first):
```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=import \
       -Dspring-boot.run.arguments=--force-reimport
```

### Step 3 — Start the API server
```bash
./mvnw spring-boot:run
```
API available at `http://localhost:8080`.

### Run tests
```bash
./mvnw test
```
Runs everything: pure unit tests (no infrastructure needed) plus the Testcontainers integration tests, which need Docker running — they spin up their own throwaway `postgis/postgis` container (separate from the `docker compose` one above, on a random port) and tear it down automatically. First run downloads the image and a small `testcontainers/ryuk` helper image; after that it's fast, since the container is started once and shared across the integration test classes rather than restarted per class.

GitHub Actions runs this same `mvn test` on every push and pull request to `main` (`.github/workflows/ci.yml`) — Docker is preinstalled on the `ubuntu-latest` runner, so no extra CI setup was needed for Testcontainers.

---

## Key Design Decisions (interview talking points)

**Why store the graph in PostgreSQL instead of a file?**
It's a real database with a proper schema, so the data survives restarts, supports concurrent writes during import, and gives a path to spatial indexing (`GiST`) for the nearest-node lookup — the flat-file alternative would make that lookup an O(n) scan no matter what.

**Why load the graph into memory instead of querying the DB on every Dijkstra step?**
Dijkstra relaxes many edges per route request. With DB queries that would be hundreds of round-trips per route calculation. Loading the graph once at startup (~1–2 seconds) makes every route calculation a pure in-memory operation completing in well under 100 ms.

**Why make DijkstraService take the adjacency map as a parameter?**
It makes the algorithm completely independent of how the graph is stored. The unit tests prove this — they pass in hand-crafted graphs with no database, no Spring context, no Docker. `AlternateRoutesService` leans on the same property: it prunes *scratch copies* of the adjacency map and reuses `DijkstraService` unmodified, rather than needing its own pathfinding logic.

**How does bidirectional Dijkstra know when to stop?**
It tracks the best "meeting point" distance found so far — any node with a known distance from *both* the forward and backward search, summed. It can stop once the two frontiers' smallest pending distances add up to something no better than that best meeting point, because both frontiers' pending distances only grow from there; nothing left unexplored could still improve on it. That's the two-sided generalisation of stopping single-direction Dijkstra the instant the target is popped.

**Why does AlternateRoutesService keep a candidate pool across rounds instead of just picking the best new option each round?**
That's what makes it Yen's algorithm rather than a greedy approximation. A candidate generated while looking for the 2nd path might turn out to be the 4th-cheapest overall — discarding it after one round would silently produce a suboptimal or duplicate set of "k shortest paths."

**What is the Haversine formula?**
It calculates the real-world distance between two lat/lng coordinates accounting for Earth's curvature. A flat Euclidean distance would be wrong because the Earth is a sphere. Haversine is accurate to ~0.5% for city-scale distances.

**Why compute travel time from distance ÷ speed instead of storing it directly?**
Each edge stores `distance_meters` and `speed_kmh`; `timeSeconds` is derived from the two when `GraphService` loads the graph into memory, not stored in the database. That means retuning the speed assumptions for a road type (e.g. deciding residential streets are really 25 km/h, not 30) only requires changing one constant and restarting — no database migration, no re-import.

**What is lazy deletion in the priority queue?**
When Dijkstra finds a shorter path to a node, it adds a new entry to the heap rather than updating the existing one (Java's `PriorityQueue` doesn't support efficient updates). Old entries become stale. The check `if (current.dist > dist[node]) skip` throws away stale entries when they're popped. This is simpler than a decrease-key operation and performs well in practice.

**Why does the Testcontainers base class start its container in a static initializer instead of a `@Container`-annotated field?**
The obvious `@Container static PostgreSQLContainer<?>` pattern, declared on an abstract base class extended by *multiple* test classes, gets stopped after the *first* subclass's tests finish — the JUnit5 extension's stop hook fires per test class, but the static field (and container) is shared by all of them. Starting it once in a static initializer and never stopping it explicitly (Testcontainers' Ryuk reaper cleans it up when the JVM exits) is the documented workaround, and it has the side benefit of one shared container across all integration test classes instead of one per class.

---

## Known Gaps

- **`NodeRepository.findNearestTo()` doesn't use PostGIS yet** — see the honest note in [Backend Services](#backend-services). The `geom` column, GiST index, and trigger all exist and work; the query just doesn't use them.
- **No frontend yet.** All of the above is reachable today only via direct API calls (curl, Postman, etc.) — see [What's Next](#whats-next-phase-3).

## What's Next (Phase 3)

- React + Leaflet.js frontend with OpenStreetMap tiles
- Click-to-pin or search-box to set start and end points
- Route (and alternates) drawn as polylines on the map
- Isochrone rendered as a shaded reachability area
- Distance and estimated travel time displayed in a side panel
