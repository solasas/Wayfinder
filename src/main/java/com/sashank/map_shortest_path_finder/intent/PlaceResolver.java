package com.sashank.map_shortest_path_finder.intent;

import com.sashank.map_shortest_path_finder.dto.LatLng;

/** Resolves a place name from the user's text to coordinates inside the supported region. */
public interface PlaceResolver {

    /**
     * @return a resolved place; never null
     * @throws PlaceNotFoundException if nothing matching lies inside the supported region
     */
    ResolvedPlace resolve(String name);

    record ResolvedPlace(String displayName, LatLng location) {}

    class PlaceNotFoundException extends RuntimeException {
        public PlaceNotFoundException(String name, String regionName) {
            super("Couldn't find '%s' inside the supported area (%s).".formatted(name, regionName));
        }
    }
}
