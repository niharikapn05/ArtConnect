package com.example.ArtConnect;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // closes finished auctions and purges expired login tokens
public class ArtConnectApplication {

	public static void main(String[] args) {
		SpringApplication.run(ArtConnectApplication.class, args);
	}

}
