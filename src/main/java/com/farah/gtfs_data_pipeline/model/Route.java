package com.farah.gtfs_data_pipeline.model;

public record Route(
        String routeId,
        String routeShortName,
        String routeLongName,
        String routeColor
) { }
