package com.premiumcars;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class PremiumCarsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PremiumCarsApplication.class, args);
    }
}
