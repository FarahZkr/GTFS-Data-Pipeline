package com.farah.gtfs_data_pipeline.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api/tiles")
public class MapConfigController {

    @Value("${mapbox.access.token}")
    private String mapboxToken;

    private final RestClient restClient = RestClient.create();

    @GetMapping("/{z}/{x}/{y}")
    public ResponseEntity<byte[]> getTile(
            @PathVariable int z,
            @PathVariable int x,
            @PathVariable int y) {

        // Construct Mapbox tile URL using server-side token
        String mapboxUrl = String.format(
                "https://api.mapbox.com/styles/v1/mapbox/light-v11/tiles/%d/%d/%d?access_token=%s",
                z, x, y, mapboxToken
        );

        // Fetch image bytes directly from Mapbox on backend
        byte[] imageBytes = restClient.get()
                .uri(mapboxUrl)
                .retrieve()
                .body(byte[].class);

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(imageBytes);
    }
}
