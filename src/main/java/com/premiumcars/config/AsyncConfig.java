package com.premiumcars.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @Async methods (inquiry e-mails) and @Scheduled jobs (ECB rates, scheduled posts, the nightly import) run on Spring
 * Boot's executors; the scheduler has a pool ({@code spring.task.scheduling.pool.size}), and long work goes to a
 * thread of its own (the import queue, the runs, the price recalculations).
 */
@Configuration
@EnableAsync
@EnableScheduling
public class AsyncConfig {
}
