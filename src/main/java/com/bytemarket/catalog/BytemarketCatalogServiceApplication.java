package com.bytemarket.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableDiscoveryClient
// Sin esto el @Scheduled de ReservationService no se ejecuta nunca, y las
// reservas que nadie confirma se quedan apartadas para siempre.
@EnableScheduling
public class BytemarketCatalogServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(BytemarketCatalogServiceApplication.class, args);
	}

}
