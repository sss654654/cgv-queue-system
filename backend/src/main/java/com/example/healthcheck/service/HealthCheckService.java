package com.example.healthcheck.service;

import com.example.healthcheck.dto.HealthCheckResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@Service
public class HealthCheckService {

    private static final Logger logger = LoggerFactory.getLogger(HealthCheckService.class);

    private final DataSource writeDataSource;
    private final DataSource readDataSource;
    private final RedisConnectionFactory redisConnectionFactory;

    public HealthCheckService(@Qualifier("writeDataSource") DataSource writeDataSource,
                             @Qualifier("readDataSource") DataSource readDataSource,
                             RedisConnectionFactory redisConnectionFactory) {
        this.writeDataSource = writeDataSource;
        this.readDataSource = readDataSource;
        this.redisConnectionFactory = redisConnectionFactory;
    }

    public HealthCheckResponse checkHealth() {
        try {
            Map<String, Object> details = new HashMap<>();
            details.put("application", "UP");

            return new HealthCheckResponse(HealthCheckResponse.Status.UP, details);
        } catch (Exception e) {
            logger.error("Health check failed", e);
            return new HealthCheckResponse(HealthCheckResponse.Status.DOWN);
        }
    }

    /**
     * Readiness Probe - K8s가 Pod를 Service 엔드포인트에 추가하기 전 확인한다.
     * DB(write/read) + Redis 모두 정상이어야 Ready.
     * 하나라도 DOWN이면 503 -> ALB가 트래픽을 보내지 않는다.
     */
    public HealthCheckResponse checkReadiness() {
        try {
            Map<String, Object> details = new HashMap<>();

            boolean writeDbUp = checkDatabaseConnection(writeDataSource, "write");
            boolean readDbUp = checkDatabaseConnection(readDataSource, "read");
            boolean redisUp = checkRedisConnection();

            details.put("writeDatabase", writeDbUp ? "UP" : "DOWN");
            details.put("readDatabase", readDbUp ? "UP" : "DOWN");
            details.put("redis", redisUp ? "UP" : "DOWN");
            details.put("application", "UP");

            if (writeDbUp && readDbUp && redisUp) {
                return new HealthCheckResponse(HealthCheckResponse.Status.UP, details);
            } else {
                return new HealthCheckResponse(HealthCheckResponse.Status.DOWN, details);
            }
        } catch (Exception e) {
            logger.error("Readiness check failed", e);
            Map<String, Object> details = new HashMap<>();
            details.put("error", e.getMessage());
            return new HealthCheckResponse(HealthCheckResponse.Status.DOWN, details);
        }
    }

    public HealthCheckResponse checkLiveness() {
        try {
            return new HealthCheckResponse(HealthCheckResponse.Status.UP);
        } catch (Exception e) {
            logger.error("Liveness check failed", e);
            return new HealthCheckResponse(HealthCheckResponse.Status.DOWN);
        }
    }

    private boolean checkDatabaseConnection(DataSource dataSource, String dbType) {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(5);
        } catch (SQLException e) {
            logger.warn("Database connection check failed for {}: {}", dbType, e.getMessage());
            return false;
        }
    }

    /**
     * Redis PING 체크. ElastiCache Multi-AZ failover (~30초) 동안 false를 반환한다.
     */
    private boolean checkRedisConnection() {
        try {
            String pong = redisConnectionFactory.getConnection().ping();
            return "PONG".equals(pong);
        } catch (Exception e) {
            logger.warn("Redis connection check failed: {}", e.getMessage());
            return false;
        }
    }
}