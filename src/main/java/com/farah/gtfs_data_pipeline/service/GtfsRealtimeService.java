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

    public GeoJsonFeatureCollection getLiveVehiclesAsGeoJson() throws IOException {
        List<GeoJsonFeature> features = new ArrayList<>();
        Map<String, Trip> trips = scheduleService.getTrips();
        Map<String, Route> routes = scheduleService.getRoutes();
        List<GtfsRealtime.VehiclePosition> positions = getLiveVehicles();

        positions.forEach((v -> {
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

            Trip trip = trips.get(tripId);
            if(trip != null){
                Route route = routes.get(trip.routeId());
                if(route != null){
                    routeName = route.routeShortName();
                    routeColor = route.routeColor();
                }
            }

            Map<String, Object> props = new HashMap<>();
            props.put("vehicle_id", vehicleId);
            props.put("trip_id", tripId);
            props.put("route_short_name", routeName);
            props.put("route_color", routeColor);
            props.put("bearing", bearing);
            props.put("speed", speed);

            features.add(new GeoJsonFeature(
                    new GeoJsonGeometry(
                            "Point",
                            List.of(longitude, latitude)),
                    props));
        }));
        return new GeoJsonFeatureCollection(features);
    }

    public List<VehiclePosition> getLiveVehicles() throws IOException {
        List<VehiclePosition> liveVehiclePositions = new ArrayList<>();

        URL url = new URL(realtimeUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

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
