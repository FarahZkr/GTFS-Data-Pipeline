package com.farah.gtfs_data_pipeline.service;

import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeature;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeatureCollection;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonGeometry;
import com.farah.gtfs_data_pipeline.model.Route;
import com.farah.gtfs_data_pipeline.model.Shape;
import com.farah.gtfs_data_pipeline.model.Stop;
import com.farah.gtfs_data_pipeline.model.Trip;
import com.farah.gtfs_data_pipeline.report.GtfsLoadReport;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class GtfsScheduleService {

    public enum LoadStatus { IDLE, LOADING, READY, FAILED }

    private LoadStatus status = LoadStatus.IDLE;
    private GtfsLoadReport lastReport;

    private final Map<String, Stop> stopLookupMap = new ConcurrentHashMap<>();
    private final Map<String, Route> routeLookupMap = new ConcurrentHashMap<>();
    private final Map<String, Trip> tripLookupMap = new ConcurrentHashMap<>();
    private final Map<String, List<Shape>> shapeLookupMap = new ConcurrentHashMap<>();
    private final List<String> foundFiles = new ArrayList<>();
    private final List<String> missingFiles = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    @Async
    public void loadGtfsData() {
        this.status = LoadStatus.LOADING;

        // Clear previous state
        stopLookupMap.clear();
        routeLookupMap.clear();
        tripLookupMap.clear();
        shapeLookupMap.clear();
        foundFiles.clear();
        missingFiles.clear();
        warnings.clear();

        try (InputStream is = getClass().getClassLoader().getResourceAsStream("gtfs.zip")){
            if (is == null) {
                this.status = LoadStatus.FAILED;
                warnings.add("Input stream came out as null. Zip file not found.");
                saveReport();
                return;
            }

            try (ZipInputStream zInStream = new ZipInputStream(is)) {
                ZipEntry entry;
                while((entry = zInStream.getNextEntry()) != null){
                    String fileName = entry.getName();
                    foundFiles.add(fileName);

                    switch (fileName) {
                        case "stops.txt" -> parseStopsData(zInStream);
                        case "routes.txt" -> parseRoutesData(zInStream);
                        case "trips.txt" -> parseTripsData(zInStream);
                        case "shapes.txt" -> parseShapesData(zInStream);
                    }
                    zInStream.closeEntry();
                }
                this.status = LoadStatus.READY;
            } catch (Exception e) {
                this.status = LoadStatus.FAILED;
                warnings.add("Error while parsing through zip file.");
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
            warnings.add("Error during initialization of input stream.");
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
}
