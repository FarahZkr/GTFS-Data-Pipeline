package com.farah.gtfs_data_pipeline;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class GtfsDataPipelineApplication {

	public static void main(String[] args) {
		SpringApplication.run(GtfsDataPipelineApplication.class, args);
	}

}
