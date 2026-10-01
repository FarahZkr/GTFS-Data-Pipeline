package com.farah.gtfs_data_pipeline.controller;

import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeature;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeatureCollection;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonGeometry;
import com.farah.gtfs_data_pipeline.model.Route;
import com.farah.gtfs_data_pipeline.model.Shape;
import com.farah.gtfs_data_pipeline.model.Stop;
import com.farah.gtfs_data_pipeline.model.Trip;
import com.farah.gtfs_data_pipeline.service.GtfsScheduleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/map")
public class GeoJsonController {

    private final GtfsScheduleService scheduleService;

    public GeoJsonController(GtfsScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/stops")
    public GeoJsonFeatureCollection getStopsAsGeoJSON(){
        Collection<Stop> stops = scheduleService.getStops();
        List<GeoJsonFeature> features = new ArrayList<>();
        for(Stop stop : stops) {
            features.add(new GeoJsonFeature(
                    new GeoJsonGeometry("Point", List.of(stop.longitude(), stop.latitude()))
                    , Map.of(
                    "stop_id", stop.stopId(),
                    "stop_name", stop.stopName()
                    )
            ));
        }
        return new GeoJsonFeatureCollection(features);
    }

    @GetMapping("/routes")
    public GeoJsonFeatureCollection getRoutesAsGeoJSON(){
        Map<String, Route> routes = scheduleService.getRoutes();
        Map<String, List<Shape>> shapes = scheduleService.getShapes();
        Collection<Trip> trips = scheduleService.getTrips();
        List<GeoJsonFeature> features = new ArrayList<>();

        shapes.forEach((shapeId, shapePoints) -> {
            List<List<Double>> coordinates = shapePoints.stream()
                    .sorted(Comparator.comparingInt(Shape::sequence))
                    .map(pt -> List.of(pt.longitude(), pt.latitude()))
                    .toList();

            // Find a matching trip
            Optional<Trip> matchingTrip = trips.stream()
                    .filter(trip -> shapeId.equals(trip.shapeId()))
                    .findFirst();

            // Default values
            String routeId = "Unknown";
            String routeName = "Unknown Route";
            String routeColor = "000000";

            // If trip found
            if (matchingTrip.isPresent()) {
                Route route = routes.get(matchingTrip.get().routeId());
                // If route found
                if (route != null) {
                    routeId = route.routeId();
                    routeName = route.routeLongName();
                    routeColor = route.routeColor() != null ? route.routeColor() : "000000";
                }
            }

            // Construct lineString feature
            GeoJsonGeometry geometry = new GeoJsonGeometry("LineString", coordinates);
            Map<String, Object> properties = Map.of(
                    "shape_id", shapeId,
                    "route_id", routeId,
                    "route_name", routeName,
                    "route_color", "#" + routeColor
            );

            features.add(new GeoJsonFeature(geometry, properties));
        });

        return new GeoJsonFeatureCollection(features);
    }

    @GetMapping("/data")
    public Collection<List<Shape>> getData(){
        return scheduleService.getShapes().values();
    }

}
