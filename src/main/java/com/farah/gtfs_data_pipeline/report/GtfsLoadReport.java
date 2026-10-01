package com.farah.gtfs_data_pipeline.report;

import java.util.List;

public record GtfsLoadReport(
    String status,
    int totalStopsLoaded,
    int totalRoutesLoaded,
    int totalTripsLoaded,
    int totalShapesLoaded,
    List<String> foundFiles,
    List<String> missingFiles,
    List<String> warnings
) { }
