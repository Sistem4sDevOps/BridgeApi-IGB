package com.IGB.BridgeApi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;

@SpringBootApplication(exclude = {ServletWebServerFactoryAutoConfiguration.class})
public class ApiRestApplication {
	public static void main(String[] args) {
		SpringApplication.run(ApiRestApplication.class, args);
	}
}