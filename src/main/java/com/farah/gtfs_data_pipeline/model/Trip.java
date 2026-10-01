package com.farah.gtfs_data_pipeline.model;

public record Trip(
      String tripId,
      String routeId,
      String shapeId
) { }
