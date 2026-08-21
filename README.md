# Map Shortest Path Finder

A mini Google Maps route planner. Pick two points on the map, and the backend finds a real driving route between them using an actual road network pulled from OpenStreetMap — not a straight line, but the roads a car could actually take.

Under the hood it's a custom **Dijkstra's algorithm** implementation, and it can optimize for two different things:
- **Shortest distance** — the fewest kilometers
- **Fastest time** — accounting for the fact that a motorway segment is faster to drive than a residential street of the same length

> **Region:** Rajahmundry urban core, Andhra Pradesh, India

---

## What This Project Demonstrates

| Concept | Where it appears |
|---|---|
| Graph algorithms (Dijkstra) | `DijkstraService.java` |
| Geospatial data (PostGIS, KNN queries) | `NodeRepository.java`, `init.sql` |
| Real-world data pipeline (OpenStreetMap) | `OsmDataFetcher`, `OsmParser`, `GraphImporter` |
| REST API design (Spring Boot) | `PathController`, `RegionController` |
| In-memory vs DB query tradeoffs | `GraphService.java` |
| Unit testing without infrastructure | `DijkstraServiceTest.java`, `SnapServiceTest.java` |

---

## Tech Stack

| Layer | Technology | Why |
|---|---|---|
| Backend | Java 17 + Spring Boot 4 | REST API + dependency injection |
| Database | PostgreSQL + PostGIS | Stores the road graph; PostGIS enables spatial (nearest-neighbor) queries |
| Map data | OpenStreetMap via Overpass API | Free, real-world road network data |
| Algorithm | Dijkstra with a min-heap | Optimal single-source shortest path on a weighted graph |
| Containers | Docker + Docker Compose | One command to spin up the database |
| Frontend *(planned)* | React + Leaflet.js | Interactive map with OpenStreetMap tiles |

---

## How It Works — End to End

```
User clicks two points on the map, picks "fastest" or "shortest"
          │
          ▼
POST /api/shortest-path  { start, end, optimize: "time" | "distance" }
          │
          ├─ SnapService ──► PostGIS KNN query ──► nearest graph node to each click
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
  — GiST spatial index on geom enables fast nearest-neighbour queries

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
  — Why in-memory? Dijkstra would need hundreds of DB queries per route otherwise.
    A city-scale graph (~5k–15k nodes) fits in a few MB of heap.

DijkstraService
  — Pure algorithm class with zero Spring/DB dependencies
  — Takes the adjacency map as a parameter → fully unit-testable with any graph
  — Also takes a WeightType (DISTANCE or TIME) telling it which of the two
    weights on each Neighbor to minimise — same algorithm, different cost function
  — Uses PriorityQueue<Entry> as a min-heap
  — Lazy deletion handles stale heap entries (simpler than decrease-key)
  — Reconstructs the path by following a prev[] map backwards from target to source
  — Reports both the total distance and total time for whichever path it found

SnapService
  — "Snap click to nearest road node"
  — Validates that the click is inside the supported region
  — Calls the PostGIS KNN query: ORDER BY geom <-> ST_Point(lng, lat) LIMIT 1
  — The <-> operator + GiST index makes this O(log n) instead of scanning all rows
```

### REST API

| Endpoint | Method | Description |
|---|---|---|
| `/api/region` | GET | Returns the supported area name, center point, and bounding box. Frontend uses this to initialize the map. |
| `/api/shortest-path` | POST | Takes start + end coordinates, returns route polyline + distance + travel time. |

**Request** (`POST /api/shortest-path`):
```json
{
  "start": { "lat": 16.975, "lng": 81.778 },
  "end":   { "lat": 16.990, "lng": 81.800 },
  "optimize": "time"
}
```
`optimize` is `"time"` or `"distance"`, and defaults to `"time"` if you leave it out — that's what most map users expect.

**Response:**
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
`distanceMeters` and `estimatedTimeSecs` are always both returned — they're the actual totals for whichever route was found, regardless of which one was optimized for.

**Error responses:**

| Situation | HTTP Status |
|---|---|
| Coordinates outside Rajahmundry | 400 Bad Request |
| No road connection between the two points | 404 Not Found |
| Import hasn't been run yet (empty graph) | 503 Service Unavailable |

---

## Project Structure

```
map_shortest_path_finder/
├── docker-compose.yml                   # PostgreSQL + PostGIS container
├── docker/postgres/init.sql             # Schema: nodes, edges, spatial index, trigger
│
└── src/main/java/com/sashank/.../
    ├── config/
    │   ├── RegionConfig.java            # Bounding box loaded from application.properties
    │   └── CorsConfig.java              # Allows React frontend (localhost:3000/5173) to call the API
    │
    ├── model/
    │   ├── Node.java                    # JPA entity: graph vertex (osm_id, lat, lng)
    │   └── Edge.java                    # JPA entity: directed road segment (from, to, distance, speed)
    │
    ├── repository/
    │   ├── NodeRepository.java          # Includes PostGIS nearest-node native query
    │   └── EdgeRepository.java          # findByFromNodeId for Dijkstra adjacency lookups
    │
    ├── pipeline/                        # Data import — runs once, not part of normal startup
    │   ├── OsmDataFetcher.java          # Calls Overpass API
    │   ├── OsmResponse.java             # Jackson mapping for the Overpass API JSON response
    │   ├── OsmParser.java               # Converts raw OSM JSON → graph nodes + edges
    │   └── GraphImporter.java           # CommandLineRunner (@Profile("import"))
    │
    ├── service/
    │   ├── GraphService.java            # Loads graph into memory at startup
    │   ├── DijkstraService.java         # Shortest path algorithm
    │   └── SnapService.java             # Nearest node lookup
    │
    ├── controller/
    │   ├── RegionController.java        # GET /api/region
    │   └── PathController.java          # POST /api/shortest-path
    │
    ├── dto/                             # Request/response data shapes
    │   ├── LatLng.java
    │   ├── PathRequest.java
    │   ├── PathResponse.java
    │   └── RegionResponse.java
    │
    └── exception/
        ├── RouteNotFoundException.java
        └── GlobalExceptionHandler.java  # Maps exceptions → HTTP status codes
```

---

## Running the Project

### Prerequisites
- Java 17+
- Maven (or use the included `./mvnw` wrapper)
- Docker

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

---

## Key Design Decisions (interview talking points)

**Why store the graph in PostgreSQL instead of a file?**
PostgreSQL with PostGIS gives a spatial index (`GiST`) that makes the "snap click to nearest road node" query run in O(log n). That nearest-node lookup is impossible to do efficiently with a flat file.

**Why load the graph into memory instead of querying the DB on every Dijkstra step?**
Dijkstra relaxes many edges per route request. With DB queries that would be hundreds of round-trips per route calculation. Loading the graph once at startup (~2 seconds) makes every route calculation a pure in-memory operation completing in under 100 ms.

**Why make DijkstraService take the adjacency map as a parameter?**
It makes the algorithm completely independent of how the graph is stored. The unit tests prove this — they pass in a hand-crafted 4-node map with no database, no Spring context, no Docker. If the storage layer changes later, `DijkstraService` doesn't change at all.

**What is the Haversine formula?**
It calculates the real-world distance between two lat/lng coordinates accounting for Earth's curvature. A flat Euclidean distance would be wrong because the Earth is a sphere. Haversine is accurate to ~0.5% for city-scale distances.

**What is the PostGIS `<->` operator?**
The KNN (k-nearest-neighbour) operator. Combined with a GiST spatial index it finds the geometrically closest point in the table in O(log n). Without the index, finding the nearest node would require computing distance to every row — O(n).

**What is lazy deletion in the priority queue?**
When Dijkstra finds a shorter path to a node, it adds a new entry to the heap rather than updating the existing one (Java's `PriorityQueue` doesn't support efficient updates). Old entries become stale. The check `if (current.dist > dist[node]) skip` throws away stale entries when they're popped. This is simpler than a decrease-key operation and performs well in practice.

**Why compute travel time from distance ÷ speed instead of storing it directly?**
Each edge stores `distance_meters` and `speed_kmh`; `timeSeconds` is derived from the two when `GraphService` loads the graph into memory, not stored in the database. That means retuning the speed assumptions for a road type (e.g. deciding residential streets are really 25 km/h, not 30) only requires changing one constant and restarting — no database migration, no re-import.

---

## What's Next (Phase 3)

- React + Leaflet.js frontend with OpenStreetMap tiles
- Click-to-pin or search-box to set start and end points
- Route drawn as a polyline on the map
- Distance and estimated travel time displayed in a side panel
