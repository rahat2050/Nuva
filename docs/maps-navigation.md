# NUVA v3.2 — Maps, routes and navigation

Eleventh safe non-financial implementation phase. NUVA builds a user-visible route handoff; it does
not read or store device location.

## Commands

```text
navigate to dhaka walking
from sylhet to dhaka public transport
directions to sunamganj
nearby pharmacy
pharmacy near me
street view 24.8949,91.8687
dhaka niye jao
```

## Request types

- Directions with optional dynamic origin
- Turn-by-turn navigation
- Nearby/category search
- Street View for validated latitude/longitude; place-name fallback opens a map search

## Travel modes

Driving, walking, bicycling and public transit. Transit uses the Maps directions URL; native
navigation deep links are used where the mode is supported.

## Safety and privacy

- Origin/destination are bounded and URL-encoded.
- Coordinates must be within latitude −90..90 and longitude −180..180.
- NUVA requests no location permission and reads no current coordinates.
- Google Maps or the selected map/browser app uses its own permission and UI.
- No ride, ticket, toll or payment is booked automatically.
