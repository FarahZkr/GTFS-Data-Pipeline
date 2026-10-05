package com.farah.gtfs_data_pipeline.service;

import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeature;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeatureCollection;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonGeometry;
import com.farah.gtfs_data_pipeline.model.Route;
import com.farah.gtfs_data_pipeline.model.Trip;
import com.google.transit.realtime.GtfsRealtime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.transit.realtime.GtfsRealtime.*;

@Service
public class GtfsRealtimeService {

    private final GtfsScheduleService scheduleService;

    public GtfsRealtimeService(GtfsScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Value("${gtfs.realtime.api-key}")
    private String apiKey;

    @Value("${gtfs.realtime.url}")
    private String realtimeUrl;

    private record CachedFeed(Instant fetchedAt, List<VehiclePosition> vehicles) {}
    private volatile CachedFeed cached;
    private static final Duration TTL = Duration.ofSeconds(10);
    private static final Duration MAX_STALE = Duration.ofMinutes(2);

    public GeoJsonFeatureCollection getLiveVehiclesAsGeoJson() throws IOException {
        List<GtfsRealtime.VehiclePosition> positions = getLiveVehicles();

        Map<String, Trip> trips = scheduleService.getTrips();
        Map<String, Route> routes = scheduleService.getRoutes();

        List<GeoJsonFeature> features = new ArrayList<>(positions.size());

        for (GtfsRealtime.VehiclePosition v : positions) {
            if(!v.hasPosition()) continue;
            // Coordinates directly from GPS unit
            double latitude = v.getPosition().getLatitude();
            double longitude = v.getPosition().getLongitude();

            // Additional telemetry
            // Direction in degrees
            float bearing = v.getPosition().hasBearing() ? v.getPosition().getBearing() : 0.0f;
            // Speed in m/s
            float speed = v.getPosition().hasSpeed() ? v.getPosition().getSpeed() : 0.0f;
            // VehicleId
            String vehicleId = v.hasVehicle() && v.getVehicle().hasId() ? v.getVehicle().getId() : "UNKNOWN";
            String tripId = v.hasTrip() ? v.getTrip().getTripId() : "";

            String routeName = "Unknown Route";
            String routeColor = "#000000";

            if (!tripId.isEmpty()) {
                Trip trip = trips.get(tripId);
                if (trip != null) {
                    Route route = routes.get(trip.routeId());
                    if (route != null) {
                        routeName = route.routeShortName();
                        routeColor = route.routeColor();
                    }
                }
            }

            Map<String, Object> props = Map.of(
                    "vehicle_id", vehicleId,
                    "trip_id", tripId,
                    "route_short_name", routeName,
                    "route_color", routeColor,
                    "bearing", bearing,
                    "speed", speed
            );

            features.add(new GeoJsonFeature(
                    new GeoJsonGeometry(
                            "Point",
                            List.of(longitude, latitude)),
                    props));
        }
        return new GeoJsonFeatureCollection(features);
    }

    // Handles caching
    public List<VehiclePosition> getLiveVehicles() throws IOException {
        CachedFeed c = cached;
        if (c != null && age(c).compareTo(TTL) < 0) return c.vehicles();

        synchronized (this) {
            c = cached;
            if (c != null && age(c).compareTo(TTL) < 0) return c.vehicles();
            try {
                List<VehiclePosition> fresh = fetchLiveVehicles();
                cached = new CachedFeed(Instant.now(), fresh);
                return fresh;
            } catch (IOException | RuntimeException e) {
                if (c != null && age(c).compareTo(MAX_STALE) < 0) return c.vehicles();
                throw e;
            }
        }
    }

    private Duration age(CachedFeed c) {
        return Duration.between(c.fetchedAt(), Instant.now());
    }

    private List<VehiclePosition> fetchLiveVehicles() throws IOException {
        URL url = new URL(realtimeUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        conn.setRequestProperty("apiKey", apiKey.trim());
        conn.setRequestProperty("accept", "application/x-protobuf");

        int responseCode = conn.getResponseCode();

        // Verifying the response code
        if (responseCode != HttpURLConnection.HTTP_OK) {
            try (InputStream errorStream = conn.getErrorStream()) {
                String errorResponse = errorStream != null
                        ? new String(errorStream.readAllBytes())
                        : "No error body returned";
                throw new RuntimeException("STM API Error [HTTP " + responseCode + "]: " + errorResponse);
            }
        }

        try (InputStream is = conn.getInputStream()) {
            FeedMessage feed = FeedMessage.parseFrom(is);

            // Iterate through every real-time entity in the feed
            return feed.getEntityList().stream()
                    .filter(FeedEntity::hasVehicle)
                    .map(FeedEntity::getVehicle)
                    .toList();
        } finally {
            conn.disconnect();
        }
    }
}
