package com.farah.gtfs_data_pipeline.service;

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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class GtfsScheduleService {

    public enum LoadStatus { IDLE, LOADING, READY, FAILED }

    private LoadStatus status = LoadStatus.IDLE;

    private final Map<String, Stop> stopLookupMap = new HashMap<>();
    private final Map<String, Route> routeLookupMap = new HashMap<>();
    private final Map<String, Trip> tripLookupMap = new HashMap<>();
    private final Map<String, List<Shape>> shapeLookupMap = new HashMap<>();
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
            if (!foundFiles.contains("agency.txt")) {
                missingFiles.add("agency.txt");
            }
            if (!foundFiles.contains("stops.txt")) {
                missingFiles.add("stops.txt");
            }
            if (!foundFiles.contains("routes.txt")) {
                missingFiles.add("routes.txt");
            }
            if (!foundFiles.contains("trips.txt")) {
                missingFiles.add("trips.txt");
            }
            if (!foundFiles.contains("shapes.txt")) {
                missingFiles.add("shapes.txt");
            }
        } catch (Exception e) {
            this.status = LoadStatus.FAILED;
            warnings.add("Error during initialization of input stream.");
        }
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
                String color = record.isMapped("route_color") ? record.get("route_color") : "000000";

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

    public GtfsLoadReport loadReport (){
        return new GtfsLoadReport(this.status.toString(),
                stopLookupMap.size(),
                routeLookupMap.size(),
                tripLookupMap.size(),
                shapeLookupMap.size(),
                foundFiles,
                missingFiles,
                warnings);
    }
}
