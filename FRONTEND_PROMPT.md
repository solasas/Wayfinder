I'm building a shortest path finder web app. The backend is already complete 
(Java Spring Boot running on http://localhost:8080). I need you to build the 
React frontend.

─── WHAT THE APP DOES ───────────────────────────────────────────────────────
A mini Google Maps for Rajahmundry, India. The user picks a start and end 
point on a real map, the backend runs Dijkstra's algorithm on real road data, 
and the shortest route is drawn on the map.

─── TECH STACK FOR FRONTEND ────────────────────────────────────────────────
- React (Vite)
- Leaflet.js + react-leaflet (for the interactive map)
- Axios (for API calls)

Setup commands:
  npm create vite@latest frontend -- --template react
  cd frontend
  npm install leaflet react-leaflet axios

─── BACKEND API CONTRACT ────────────────────────────────────────────────────

1. GET http://localhost:8080/api/region
   Use on app load to center the map.
   
   Response:
   {
     "name": "Rajahmundry",
     "center": { "lat": 16.985, "lng": 81.79 },
     "bbox": { "south": 16.96, "north": 17.01, "west": 81.76, "east": 81.82 }
   }

2. POST http://localhost:8080/api/shortest-path
   Call when user has placed both start and end pins.
   
   Request body:
   {
     "start": { "lat": 16.975, "lng": 81.778 },
     "end":   { "lat": 16.990, "lng": 81.800 }
   }
   
   Success response (200):
   {
     "path": [
       { "lat": 16.975, "lng": 81.778 },
       { "lat": 16.976, "lng": 81.779 },
       ...
       { "lat": 16.990, "lng": 81.800 }
     ],
     "distanceMeters": 3156.4,
     "estimatedTimeSecs": 378
   }
   
   Error responses:
   - 400: coordinates outside Rajahmundry  → { "error": "..." }
   - 404: no road connection between points → { "error": "..." }
   - 503: graph not loaded yet             → { "error": "..." }

─── WHAT TO BUILD ───────────────────────────────────────────────────────────

1. MAP
   - Full-screen Leaflet map using OpenStreetMap tiles
   - On load: call GET /api/region and center the map there at zoom 14
   - Optional: draw a faint rectangle showing the supported bounding box

2. CLICK TO PLACE PINS
   - First click  → green marker (start point)
   - Second click → red marker (end point)
   - Third click  → clears both markers and the route, starts over
   - Show a small instruction text: "Click to set start point" /
     "Click to set end point" / "Click anywhere to reset"

3. ROUTE DISPLAY
   - As soon as both pins are placed, call POST /api/shortest-path
   - Show a loading indicator while waiting
   - On success: draw a blue polyline (weight 5) along the path array
   - Fit the map bounds to show the full route

4. INFO PANEL
   - Fixed panel (top-right or bottom) showing:
     - Distance: e.g. "3.2 km"
     - Estimated time: e.g. "6 min"  (estimatedTimeSecs / 60, rounded)
   - Hide the panel when no route is shown

5. ERROR HANDLING
   - If API returns 400: show "Start or end point is outside the supported area"
   - If API returns 404: show "No road connection found between these points.
     Try different locations."
   - If API returns 503: show "Map data is still loading. Please wait."
   - Show errors as a dismissable banner at the top of the screen

─── IMPORTANT LEAFLET SETUP ─────────────────────────────────────────────────
Leaflet requires its CSS to be imported. Add this to main.jsx or App.jsx:

  import 'leaflet/dist/leaflet.css'

Leaflet's default marker icons break with Vite. Fix with this in App.jsx:

  import L from 'leaflet'
  import markerIcon2x from 'leaflet/dist/images/marker-icon-2x.png'
  import markerIcon from 'leaflet/dist/images/marker-icon.png'
  import markerShadow from 'leaflet/dist/images/marker-shadow.png'

  delete L.Icon.Default.prototype._getIconUrl
  L.Icon.Default.mergeOptions({
    iconRetinaUrl: markerIcon2x,
    iconUrl: markerIcon,
    shadowUrl: markerShadow,
  })

─── FILE STRUCTURE TO CREATE ────────────────────────────────────────────────
src/
├── App.jsx           ← main component, holds all state
├── main.jsx          ← entry point, import leaflet CSS here
├── components/
│   ├── MapView.jsx   ← the Leaflet map, markers, polyline
│   └── InfoPanel.jsx ← distance + time display
├── hooks/
│   └── useRoute.js   ← API call logic (axios POST)
└── App.css           ← basic layout styles

─── STATE TO MANAGE IN App.jsx ─────────────────────────────────────────────
- startPin: { lat, lng } or null
- endPin:   { lat, lng } or null
- route:    { path: [{lat,lng},...], distanceMeters, estimatedTimeSecs } or null
- loading:  boolean
- error:    string or null

─── STYLE REQUIREMENTS ──────────────────────────────────────────────────────
- Map takes full viewport height
- Info panel is overlaid on the map (position: absolute), not beside it
- Clean, minimal design — this is a portfolio project
- The route polyline should be clearly visible (blue, weight 5, opacity 0.8)
