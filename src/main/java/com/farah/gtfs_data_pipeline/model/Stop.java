package com.farah.gtfs_data_pipeline.model;

public record Stop (
    String stopId,
    String stopName,
    double latitude,
    double longitude
) { }
