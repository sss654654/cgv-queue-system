package com.example.config;

import com.example.admission.ws.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;

/**
 * GracefulShutdownManager - Pod 종료 시 안전한 리소스 정리
 *
 * preStop hook (5초) -> SIGTERM -> Spring lifecycle (30초) 순으로 동작한다.
 *
 * 순서:
 * 1. QueueProcessor 스케줄러 중지 (새 배치 처리 방지)
 * 2. In-flight 처리 완료 대기 (최대 10초)
 * 3. WebSocket 클라이언트에 RECONNECT_SOON 알림 전송
 * 4. Redis 커넥션 풀 정리
 *
 * 2.2 spec: @PreDestroy 25초 + ALB deregistration_delay 30초
 */
@Component
public class GracefulShutdownManager {

    private static final Logger logger = LoggerFactory.getLogger(GracefulShutdownManager.class);
    private static final int MAX_DRAIN_WAIT_SECONDS = 10;

    private final ThreadPoolTaskScheduler taskScheduler;
    private final WebSocketSessionManager webSocketSessionManager;
    private final RedisConnectionFactory redisConnectionFactory;

    public GracefulShutdownManager(ThreadPoolTaskScheduler taskScheduler,
                                   WebSocketSessionManager webSocketSessionManager,
                                   RedisConnectionFactory redisConnectionFactory) {
        this.taskScheduler = taskScheduler;
        this.webSocketSessionManager = webSocketSessionManager;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    @PreDestroy
    public void onShutdown() {
        logger.info("Graceful shutdown initiated");

        // 1. Stop QueueProcessor scheduler (no new batch processing)
        try {
            logger.info("[1/4] Stopping task scheduler...");
            taskScheduler.shutdown();
            logger.info("[1/4] Task scheduler stopped");
        } catch (Exception e) {
            logger.warn("[1/4] Failed to stop task scheduler", e);
        }

        // 2. Wait for in-flight processing (max 10s)
        try {
            logger.info("[2/4] Waiting for in-flight tasks (max {}s)...", MAX_DRAIN_WAIT_SECONDS);
            boolean terminated = taskScheduler.getScheduledThreadPoolExecutor()
                    .awaitTermination(MAX_DRAIN_WAIT_SECONDS, TimeUnit.SECONDS);
            if (terminated) {
                logger.info("[2/4] All in-flight tasks completed");
            } else {
                logger.warn("[2/4] Timed out waiting for in-flight tasks, forcing shutdown");
                taskScheduler.getScheduledThreadPoolExecutor().shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("[2/4] Interrupted while waiting for in-flight tasks", e);
        }

        // 3. Drain WebSocket sessions (send RECONNECT to each session individually, then close)
        try {
            logger.info("[3/4] Draining WebSocket sessions via WebSocketSessionManager...");
            webSocketSessionManager.prepareForShutdown();
            logger.info("[3/4] WebSocket session drain completed");
        } catch (Exception e) {
            logger.warn("[3/4] Failed to drain WebSocket sessions", e);
        }

        // 4. Close Redis connection pool
        try {
            logger.info("[4/4] Closing Redis connection pool...");
            if (redisConnectionFactory instanceof AutoCloseable closeable) {
                closeable.close();
            }
            logger.info("[4/4] Redis connection pool closed");
        } catch (Exception e) {
            logger.warn("[4/4] Failed to close Redis connection pool", e);
        }

        logger.info("Graceful shutdown completed");
    }
}
