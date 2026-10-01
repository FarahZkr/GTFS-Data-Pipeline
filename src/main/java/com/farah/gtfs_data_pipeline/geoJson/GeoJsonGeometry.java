package com.farah.gtfs_data_pipeline.geoJson;

public record GeoJsonGeometry(
        String type,
        Object coordinates
)
{ }
