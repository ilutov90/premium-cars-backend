package com.premiumcars.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** @Async methods (inquiry e-mails) and @Scheduled jobs (ECB exchange rates) run on Spring Boot's executors. */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
