package com.farah.gtfs_data_pipeline.model;

public record Shape(
    String shapeId,
    double latitude,
    double longitude,
    int sequence
) { }
