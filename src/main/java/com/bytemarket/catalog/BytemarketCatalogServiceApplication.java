package com.bytemarket.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;

@SpringBootApplication
@EnableDiscoveryClient
public class BytemarketCatalogServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(BytemarketCatalogServiceApplication.class, args);
	}

}
