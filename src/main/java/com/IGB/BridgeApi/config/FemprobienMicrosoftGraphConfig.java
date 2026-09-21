package com.IGB.BridgeApi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class FemprobienMicrosoftGraphConfig {

    @Bean(name = "femprobienGraphRestTemplate")
    public RestTemplate femprobienGraphRestTemplate() {

        return new RestTemplate();
    }
}
