package com.farah.gtfs_data_pipeline.controller;

import com.farah.gtfs_data_pipeline.model.Stop;
import com.farah.gtfs_data_pipeline.report.GtfsLoadReport;
import com.farah.gtfs_data_pipeline.service.GtfsScheduleService;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransitController {

    private final GtfsScheduleService scheduleService;

    public TransitController(GtfsScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @GetMapping("/load")
    public void loadGtfsData() {
        scheduleService.loadGtfsData();
    }

    @GetMapping("/status")
    public GtfsLoadReport getStatus() {
        return scheduleService.loadReport();
    }
}
