package com.example.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * SchedulingConfig - @Scheduled 활성화 + TaskScheduler Bean
 *
 * ThreadPoolTaskScheduler를 Bean으로 등록하여:
 * 1. QueueProcessor @Scheduled가 이 스케줄러를 사용
 * 2. GracefulShutdownManager가 @PreDestroy에서 스케줄러를 중지
 *
 * poolSize=4: QueueProcessor, SessionTimeoutProcessor,
 *             RealtimeStatsBroadcaster, LoadBalancingOptimizer
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {

    @Bean
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("queue-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }
}
