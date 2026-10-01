package com.farah.gtfs_data_pipeline.controller;

import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeature;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonFeatureCollection;
import com.farah.gtfs_data_pipeline.geoJson.GeoJsonGeometry;
import com.farah.gtfs_data_pipeline.model.Route;
import com.farah.gtfs_data_pipeline.model.Shape;
import com.farah.gtfs_data_pipeline.model.Stop;
import com.farah.gtfs_data_pipeline.model.Trip;
import com.farah.gtfs_data_pipeline.service.GtfsRealtimeService;
import com.farah.gtfs_data_pipeline.service.GtfsScheduleService;
import com.google.transit.realtime.GtfsRealtime;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.*;

@RestController
@RequestMapping("/api/map")
public class GeoJsonController {

    private final GtfsScheduleService scheduleService;
    private final GtfsRealtimeService rtScheduleService;

    public GeoJsonController(GtfsScheduleService scheduleService, GtfsRealtimeService rtScheduleService) {
        this.scheduleService = scheduleService;
        this.rtScheduleService = rtScheduleService;
    }

    @GetMapping("/stops")
    public GeoJsonFeatureCollection getStopsAsGeoJSON(){
        return scheduleService.getStopsAsGeoJSON();
    }

    @GetMapping("/routes")
    public GeoJsonFeatureCollection getRoutesAsGeoJSON(){
        return scheduleService.getRoutesAsGeoJSON();
    }

    @GetMapping("/vehicles")
    public GeoJsonFeatureCollection getLiveVehicles() throws IOException {
        return rtScheduleService.getLiveVehiclesAsGeoJson();
    }

}
