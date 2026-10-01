package com.farah.gtfs_data_pipeline.model;

public record GeoJsonGeometry(
        String type,
        Double[][] coordinates
)
{ }
