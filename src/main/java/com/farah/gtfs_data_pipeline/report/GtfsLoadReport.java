package com.farah.gtfs_data_pipeline.report;

import java.util.List;

public record GtfsLoadReport(
    String status,
    int totalStops,
    int totalRoutes,
    int totalTrips,
    int totalShapes,
    int orphanTripsCount,
    int outOfBoundsStopsCount,
    List<String> foundFiles,
    List<String> missingFiles,
    List<String> warnings
) { }
