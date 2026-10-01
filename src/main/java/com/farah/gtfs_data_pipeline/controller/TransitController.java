package com.farah.gtfs_data_pipeline.controller;

import com.farah.gtfs_data_pipeline.report.GtfsLoadReport;
import com.farah.gtfs_data_pipeline.service.GtfsScheduleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransitController {

    private final GtfsScheduleService scheduleService;

    public TransitController(GtfsScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/load")
    public ResponseEntity<Map<String, String>> loadGtfsData() {
        scheduleService.loadGtfsData();
        return ResponseEntity.accepted().body(Map.of(
                "message", "GTFS background ingestion started.",
                "statusCheckUrl", "/api/status"
        ));
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
                "status", scheduleService.getStatus(),
                "report", scheduleService.getLastReport() != null ? scheduleService.getLastReport() : "No report generated yet."
        ));
    }
}
