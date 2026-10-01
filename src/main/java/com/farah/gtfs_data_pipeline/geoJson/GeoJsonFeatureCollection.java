package com.farah.gtfs_data_pipeline.geoJson;

import java.util.List;

public record GeoJsonFeatureCollection(
        String type,
        List<GeoJsonFeature> features
) {
    public GeoJsonFeatureCollection(List<GeoJsonFeature> features) {
        this("FeatureCollection", features);
    }
}
