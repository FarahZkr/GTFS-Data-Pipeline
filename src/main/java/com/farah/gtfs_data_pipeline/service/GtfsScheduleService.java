package com.farah.gtfs_data_pipeline.service;

import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeature;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeatureCollection;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonGeometry;
import com.farah.gtfs_data_pipeline.model.Route;
import com.farah.gtfs_data_pipeline.model.Shape;
import com.farah.gtfs_data_pipeline.model.Stop;
import com.farah.gtfs_data_pipeline.model.Trip;
import com.farah.gtfs_data_pipeline.report.GtfsLoadReport;
import com.github.davidmoten.rtree.Entry;
import com.github.davidmoten.rtree.RTree;
import com.github.davidmoten.rtree.geometry.Geometries;
import com.github.davidmoten.rtree.geometry.Point;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class GtfsScheduleService {

    @Value("${gtfs.schedule.url}")
    private String scheduleUrl;

    // Bounding area for STM
    private static final double MIN_LAT = 45.0;
    private static final double MAX_LAT = 46.0;
    private static final double MIN_LON = -74.0;
    private static final double MAX_LON = -73.0;
    private RTree<Stop, Point> spatialIndex = RTree.create();

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GtfsScheduleService.class);

    public enum LoadStatus { IDLE, LOADING, READY, FAILED }

    private volatile LoadStatus status = LoadStatus.IDLE;
    private GtfsLoadReport lastReport;

    private final Map<String, Stop> stopLookupMap = new ConcurrentHashMap<>();
    private final Map<String, Route> routeLookupMap = new ConcurrentHashMap<>();
    private final Map<String, Trip> tripLookupMap = new ConcurrentHashMap<>();
    private final Map<String, List<Shape>> shapeLookupMap = new ConcurrentHashMap<>();
    private final List<String> foundFiles = new ArrayList<>();
    private final List<String> missingFiles = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();
    private int outOfBoundsStopsCount = 0;
    private int orphanTripsCount = 0;

    // Caching system
    private volatile Instant lastSuccess;
    private volatile Instant lastAttempt;
    private static final Duration MAX_AGE = Duration.ofHours(12);
    private static final Duration RETRY_COOLDOWN = Duration.ofMinutes(2);

    private String getEffectiveUrl() {
        return (scheduleUrl != null && !scheduleUrl.isBlank())
                ? scheduleUrl
                : "https://www.stm.info/sites/default/files/gtfs/gtfs_stm.zip";
    }

    public void loadStopsFromConfiguredUrl() throws Exception {
        Instant now = Instant.now();

        if (this.status == LoadStatus.LOADING) {
            log.info("GTFS ingestion is currently in progress. Skipping duplicate load request.");
            return;
        }

        boolean fresh = status == LoadStatus.READY && lastSuccess != null
                && Duration.between(lastSuccess, now).compareTo(MAX_AGE) < 0;
        boolean failedRecently = status == LoadStatus.FAILED && lastAttempt != null
                && Duration.between(lastAttempt, now).compareTo(RETRY_COOLDOWN) < 0;

        if (fresh || failedRecently) {
            String msg = "Skipping GTFS download (Fresh cache or in retry cooldown).";
            log.info(msg);
            warnings.add(msg);
            return;
        }

        lastAttempt = now;
        loadGtfsData(getEffectiveUrl());
    }

    @Async
    public void loadGtfsData(String zipUrl) throws Exception {
        URL url = new URI(zipUrl).toURL();

        this.status = LoadStatus.LOADING;

        // Clear previous state
        stopLookupMap.clear();
        routeLookupMap.clear();
        tripLookupMap.clear();
        shapeLookupMap.clear();
        foundFiles.clear();
        missingFiles.clear();
        warnings.clear();
        outOfBoundsStopsCount = 0;
        orphanTripsCount = 0;

        try (ZipInputStream zipIn = new ZipInputStream(url.openStream())) {
            ZipEntry entry;
            while((entry = zipIn.getNextEntry()) != null){
                String fileName = entry.getName();
                foundFiles.add(fileName);

                switch (fileName) {
                    case "stops.txt" -> parseStopsData(zipIn);
                    case "routes.txt" -> parseRoutesData(zipIn);
                    case "trips.txt" -> parseTripsData(zipIn);
                    case "shapes.txt" -> parseShapesData(zipIn);
                }
                zipIn.closeEntry();
            }
            // Retrieving information on all missing files
            List<String> requiredFiles = List.of("agency.txt", "stops.txt", "routes.txt", "trips.txt", "shapes.txt");
            for (String file : requiredFiles) {
                if (!foundFiles.contains(file)) {
                    missingFiles.add(file);
                }
            }
        } catch (Exception e) {
            this.status = LoadStatus.FAILED;
            warnings.add("Error while parsing through zip file. " + e.toString());
            log.error("GTFS ingestion failed: {}", e.toString(), e);
        }
        buildRtree();
        if (this.status == LoadStatus.LOADING) {
            this.status = LoadStatus.READY;
            this.lastSuccess = Instant.now();
        }
        saveReport();
    }

    // Parse stops CSV
    private void parseStopsData(ZipInputStream zipIn) {
        try{
            Reader reader = new InputStreamReader(zipIn);
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .build();

            CSVParser parser = new CSVParser(reader, format);
            for (CSVRecord record : parser) {
                String id = record.get("stop_id");
                String name = record.get("stop_name");
                double lat = Double.parseDouble(record.get("stop_lat"));
                double lon = Double.parseDouble(record.get("stop_lon"));

                if (lat < MIN_LAT || lat > MAX_LAT || lon < MIN_LON || lon > MAX_LON) {
                    outOfBoundsStopsCount++;
                    warnings.add(String.format("Stop ID %s ('%s') has out-of-bounds coordinates: [%f, %f]", id, name, lat, lon));
                }

                Stop stop = new Stop(id, name, lat, lon);
                stopLookupMap.put(id, stop);
            }
        } catch (Exception e){
            warnings.add("Error in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    // Parse routes CSV
    private void parseRoutesData(ZipInputStream zipIn) {
        try{
            Reader reader = new InputStreamReader(zipIn);
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .build();

            CSVParser parser = new CSVParser(reader, format);
            for (CSVRecord record : parser) {
                String id = record.get("route_id");
                String sName = record.get("route_short_name");
                String lName = record.get("route_long_name");
                String color = record.isMapped("route_color") ? "#" + record.get("route_color") : "#000000";

                Route route = new Route(id, sName, lName, color);
                routeLookupMap.put(id, route);
            }
        } catch (Exception e){
            warnings.add("Error in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    // Parse trip CSV
    private void parseTripsData(ZipInputStream zipIn) {
        try{
            Reader reader = new InputStreamReader(zipIn);
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .build();

            CSVParser parser = new CSVParser(reader, format);
            for (CSVRecord record : parser) {
                String tripId = record.get("trip_id");
                String routeId = record.get("route_id");
                String shapeId = record.get("shape_id");

                if (!routeLookupMap.containsKey(routeId)) {
                    orphanTripsCount++;
                    warnings.add(String.format("Orphan Trip ID %s references non-existent Route ID %s", tripId, routeId));
                }

                Trip trip = new Trip(tripId, routeId, shapeId);
                tripLookupMap.put(tripId, trip);
            }
        } catch (Exception e){
            warnings.add("Error in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    // Parse shapes CSV
    private void parseShapesData(ZipInputStream zipIn) {
        try{
            Reader reader = new InputStreamReader(zipIn);
            CSVFormat format = CSVFormat.DEFAULT.builder()
                    .setHeader()
                    .setSkipHeaderRecord(true)
                    .setIgnoreHeaderCase(true)
                    .setTrim(true)
                    .build();

            CSVParser parser = new CSVParser(reader, format);
            for (CSVRecord record : parser) {
                String shapeId = record.get("shape_id");
                double lat = Double.parseDouble(record.get("shape_pt_lat"));
                double lon = Double.parseDouble(record.get("shape_pt_lon"));
                int sequence = Integer.parseInt(record.get("shape_pt_sequence"));

                Shape shape = new Shape(shapeId, lat, lon, sequence);
                shapeLookupMap.putIfAbsent(shapeId, new ArrayList<>());
                shapeLookupMap.get(shapeId).add(shape);
            }
        } catch (Exception e){
            warnings.add("Error in " + e.getStackTrace()[0].getMethodName() + ": " + e.getMessage());
        }
    }

    public GeoJsonFeatureCollection getStopsAsGeoJSON(){
        Collection<Stop> stops = getStops();
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

    public GeoJsonFeatureCollection getRoutesAsGeoJSON(){
        Map<String, Route> routes = getRoutes();
        Map<String, List<Shape>> shapes = getShapes();
        Collection<Trip> trips = getTrips().values();
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
                    routeColor = route.routeColor() != null ? route.routeColor() : "#000000";
                }
            }

            // Construct lineString feature
            GeoJsonGeometry geometry = new GeoJsonGeometry("LineString", coordinates);
            Map<String, Object> properties = Map.of(
                    "shape_id", shapeId,
                    "route_id", routeId,
                    "route_name", routeName,
                    "route_color", routeColor
            );

            features.add(new GeoJsonFeature(geometry, properties));
        });

        return new GeoJsonFeatureCollection(features);
    }

    // Return the list of stops
    public Collection<Stop> getStops() {
        return Collections.unmodifiableMap(stopLookupMap).values();
    }

    // Return the list of routes
    public Map<String, Route> getRoutes() {
        return Collections.unmodifiableMap(routeLookupMap);
    }

    // Return the list of trips
    public Map<String, Trip> getTrips() {
        return Collections.unmodifiableMap(tripLookupMap);
    }

    // Return the list of shapes
    public  Map<String, List<Shape>> getShapes() {
        return Collections.unmodifiableMap(shapeLookupMap);
    }

    private void saveReport() {
        this.lastReport = new GtfsLoadReport(
                this.status.toString(),
                stopLookupMap.size(),
                routeLookupMap.size(),
                tripLookupMap.size(),
                shapeLookupMap.size(),
                orphanTripsCount,
                outOfBoundsStopsCount,
                new ArrayList<>(foundFiles),
                new ArrayList<>(missingFiles),
                new ArrayList<>(warnings)
        );
    }

    public LoadStatus getStatus() {
        return status;
    }

    public GtfsLoadReport getLastReport() {
        return lastReport;
    }

    // USING DAVID MOTEN'S R-TREE LIBRARY TO SEARCH EFFICIENTLY FOR STOPS

    // Load up stops in R-Tree to make search faster
    public void buildRtree() {
        spatialIndex = RTree.create(); // Reset index
        for (Stop stop : stopLookupMap.values()) {
            // R-Tree indexes by Point(longitude, latitude)
            spatialIndex = spatialIndex.add(stop, Geometries.point(stop.longitude(), stop.latitude()));
        }
    }

    // O(log N) Spatial Radius Search
    public List<Stop> findNearbyStops(double userLat, double userLon, double radiusMeters) {
        // Convert radial meters to approximate bounding degree offset
        double latOffset = radiusMeters / 111_000.0;
        double lonOffset = radiusMeters / (111_000.0 * Math.cos(Math.toRadians(userLat)));

        // Fast R-Tree Bounding Box Search O(log N)
        return spatialIndex.search(Geometries.rectangle(
                        userLon - lonOffset, userLat - latOffset,
                        userLon + lonOffset, userLat + latOffset
                ))
                .map(Entry::value)
                // 2. Precise Haversine distance refinement on candidate subset
                .filter(stop -> calculateHaversineMeters(userLat, userLon, stop.latitude(), stop.longitude()) <= radiusMeters)
                .toList()
                .toBlocking()
                .single();
    }

    // Calculating the distance between point A to point B
    private double calculateHaversineMeters(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6_371_000; // Earth radius in meters
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
