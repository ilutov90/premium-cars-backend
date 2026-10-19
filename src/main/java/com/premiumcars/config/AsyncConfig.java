package com.premiumcars.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;

/** @Async methods (inquiry e-mails) run on Spring Boot's task executor. */
@Configuration
@EnableAsync
public class AsyncConfig {
}
