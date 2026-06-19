package com.example.admission.service;

import com.example.admission.dto.AdmissionMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class AdmissionMetricsService {
    private static final Logger logger = LoggerFactory.getLogger(AdmissionMetricsService.class);

    private static final String ACTIVE_MOVIES = "active_movies";
    private static final String WAITING_MOVIES = "waiting_movies";

    private final RedisTemplate<String, String> redisTemplate;
    private final AdmissionService admissionService;
    private final DynamicSessionCalculator sessionCalculator;

    private final Map<String, AtomicLong> realtimeMetrics = new ConcurrentHashMap<>();
    private final Map<String, Deque<Long>> historicalData = new ConcurrentHashMap<>();
    private final int HISTORY_LIMIT = 100;

    public AdmissionMetricsService(RedisTemplate<String, String> redisTemplate,
                                   AdmissionService admissionService,
                                   DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.admissionService = admissionService;
        this.sessionCalculator = sessionCalculator;

        realtimeMetrics.put("totalEntriesProcessed", new AtomicLong(0));
        realtimeMetrics.put("totalTimeouts", new AtomicLong(0));
        realtimeMetrics.put("totalQueueJoins", new AtomicLong(0));
        realtimeMetrics.put("totalBatchProcesses", new AtomicLong(0));
        realtimeMetrics.put("totalProcessingTimeMs", new AtomicLong(0));
        realtimeMetrics.put("throughputLastMinute", new AtomicLong(0));
    }

    @Scheduled(fixedDelay = 10000)
    public void collectSystemMetrics() {
        try {
            long allActiveSessions = getAllActiveSessionsCount();
            long allWaitingUsers = getAllWaitingUsersCount();

            updateHistory("queueSizeHistory", allWaitingUsers);

            DynamicSessionCalculator.SessionCalculationInfo config = sessionCalculator.getCalculationInfo();
            if (config.calculatedMaxSessions() > 0) {
                long utilization = (allActiveSessions * 100) / config.calculatedMaxSessions();
                updateHistory("podUtilizationHistory", utilization);
            }

            logger.debug("시스템 메트릭 수집: 활성 세션 = {}, 대기자 = {}", allActiveSessions, allWaitingUsers);
        } catch (Exception e) {
            logger.error("시스템 메트릭 수집 중 오류 발생", e);
        }
    }

    @Scheduled(fixedRate = 60000)
    public void recordThroughput() {
        long throughput = realtimeMetrics.get("throughputLastMinute").getAndSet(0);
        updateHistory("throughputHistory", throughput);
        logger.info("분당 처리량 메트릭: {}명", throughput);
    }

    public AdmissionMetrics getCurrentMetrics() {
        DynamicSessionCalculator.SessionCalculationInfo config = sessionCalculator.getCalculationInfo();
        long totalProcessed = realtimeMetrics.get("totalEntriesProcessed").get();
        long totalTime = realtimeMetrics.get("totalProcessingTimeMs").get();
        long avgProcessingTime = totalProcessed > 0 ? totalTime / totalProcessed : 0;

        return new AdmissionMetrics(
                System.currentTimeMillis(),
                config.currentPodCount(),
                config.calculatedMaxSessions(),
                getAllActiveSessionsCount(),
                getAllWaitingUsersCount(),
                totalProcessed,
                realtimeMetrics.get("totalTimeouts").get(),
                realtimeMetrics.get("totalQueueJoins").get(),
                realtimeMetrics.get("totalBatchProcesses").get(),
                avgProcessingTime,
                new ArrayList<>(historicalData.getOrDefault("throughputHistory", new LinkedList<>())),
                new ArrayList<>(historicalData.getOrDefault("queueSizeHistory", new LinkedList<>())),
                new ArrayList<>(historicalData.getOrDefault("podUtilizationHistory", new LinkedList<>()))
        );
    }

    public void recordTimeout(String movieId, long count) {
        realtimeMetrics.get("totalTimeouts").addAndGet(count);
    }

    public void resetMetrics() {
        realtimeMetrics.values().forEach(v -> v.set(0));
        historicalData.clear();
        logger.info("모든 메트릭이 초기화되었습니다");
    }

    private void updateHistory(String key, long value) {
        historicalData.computeIfAbsent(key, k -> new LinkedList<>()).addLast(value);
        Deque<Long> queue = historicalData.get(key);
        while (queue.size() > HISTORY_LIMIT) {
            queue.removeFirst();
        }
    }

    /**
     * SCAN 제거: Sorted Set ZCARD로 활성 세션 수 계산
     * Key pattern: sessions:{movieId}:active (Sorted Set)
     */
    public long getAllActiveSessionsCount() {
        try {
            Set<String> movieIds = redisTemplate.opsForSet().members(ACTIVE_MOVIES);
            if (movieIds == null || movieIds.isEmpty()) {
                return 0L;
            }

            long total = 0L;
            for (String movieId : movieIds) {
                try {
                    String activeSessionsKey = "sessions:{" + movieId + "}:active";
                    Long sessionCount = redisTemplate.opsForZSet().zCard(activeSessionsKey);
                    if (sessionCount != null) {
                        total += sessionCount;
                    }
                } catch (Exception e) {
                    logger.warn("영화 {} 활성 세션 수 조회 실패", movieId, e);
                }
            }

            logger.debug("총 활성 세션 수 계산 완료: {} (영화 {}개)", total, movieIds.size());
            return total;

        } catch (Exception e) {
            logger.error("활성 세션 수 계산 중 오류 발생", e);
            return 0L;
        }
    }

    /**
     * SCAN 제거: Sorted Set ZCARD로 대기 사용자 수 계산
     * Key pattern: sessions:{movieId}:waiting (Sorted Set)
     */
    public long getAllWaitingUsersCount() {
        try {
            // waiting_movies와 active_movies의 합집합으로 모든 영화 ID 수집
            Set<String> allMovieIds = new HashSet<>();
            
            Set<String> waitingMovies = redisTemplate.opsForSet().members(WAITING_MOVIES);
            Set<String> activeMovies = redisTemplate.opsForSet().members(ACTIVE_MOVIES);
            
            if (waitingMovies != null) allMovieIds.addAll(waitingMovies);
            if (activeMovies != null) allMovieIds.addAll(activeMovies);
            
            if (allMovieIds.isEmpty()) {
                return 0L;
            }

            long total = 0L;
            for (String movieId : allMovieIds) {
                try {
                    String waitingQueueKey = "sessions:{" + movieId + "}:waiting";
                    Long waitingCount = redisTemplate.opsForZSet().zCard(waitingQueueKey);
                    if (waitingCount != null) {
                        total += waitingCount;
                    }
                } catch (Exception e) {
                    logger.warn("영화 {} 대기 사용자 수 조회 실패", movieId, e);
                }
            }

            logger.debug("총 대기 사용자 수 계산 완료: {} (영화 {}개)", total, allMovieIds.size());
            return total;

        } catch (Exception e) {
            logger.error("대기 사용자 수 계산 중 오류 발생", e);
            return 0L;
        }
    }

    /**
     * 🔹 성능 분석 보고서 생성
     */
    public Map<String, Object> getPerformanceAnalysis() {
        Map<String, Object> analysis = new HashMap<>();
        
        try {
            AdmissionMetrics metrics = getCurrentMetrics();
            double avgThroughput = metrics.getAverageThroughputPerMinute();
            double avgUtilization = metrics.podUtilizationHistory().stream()
                    .mapToLong(Long::longValue)
                    .average()
                    .orElse(0.0);

            List<Long> queueSizes = metrics.queueSizeHistory();
            boolean queueGrowing = queueSizes.size() >= 2 &&
                    queueSizes.get(queueSizes.size() - 1) > queueSizes.get(queueSizes.size() - 2);

            analysis.put("avgThroughputPerMinute", Math.round(avgThroughput));
            analysis.put("avgPodUtilization", Math.round(avgUtilization * 10) / 10.0);
            analysis.put("isQueueGrowing", queueGrowing);
            analysis.put("recommendScaleUp", avgUtilization > 80 && queueGrowing);
            analysis.put("recommendScaleDown", avgUtilization < 30 && !queueGrowing);
            analysis.put("systemHealth", avgUtilization < 90 ? "HEALTHY" : "OVERLOADED");
            
            // 추가 통계 정보
            analysis.put("currentActiveSessions", metrics.currentActiveSessions());
            analysis.put("currentWaitingUsers", metrics.currentWaitingUsers());
            analysis.put("maxSessions", metrics.maxSessions());
            analysis.put("totalEntriesProcessed", metrics.totalEntriesProcessed());
            
        } catch (Exception e) {
            logger.error("성능 분석 중 오류", e);
            analysis.put("error", e.getMessage());
            analysis.put("systemHealth", "ERROR");
        }
        
        return analysis;
    }

    /**
     * 🔹 메트릭 기록 메서드들
     */
    public void recordEntry(String movieId) {
        realtimeMetrics.get("totalEntriesProcessed").incrementAndGet();
        realtimeMetrics.get("throughputLastMinute").incrementAndGet();
    }

    public void recordQueueJoin(String movieId) {
        realtimeMetrics.get("totalQueueJoins").incrementAndGet();
    }

    public void recordBatchProcess(String movieId, int batchSize) {
        realtimeMetrics.get("totalBatchProcesses").incrementAndGet();
        realtimeMetrics.get("totalEntriesProcessed").addAndGet(batchSize);
        realtimeMetrics.get("throughputLastMinute").addAndGet(batchSize);
    }

    public void recordProcessingTime(long processingTimeMs) {
        realtimeMetrics.get("totalProcessingTimeMs").addAndGet(processingTimeMs);
    }

    /**
     * 🔹 시스템 상태 요약 정보
     */
    public Map<String, Object> getSystemSummary() {
        Map<String, Object> summary = new HashMap<>();
        
        try {
            long activeSessions = getAllActiveSessionsCount();
            long waitingUsers = getAllWaitingUsersCount();
            DynamicSessionCalculator.SessionCalculationInfo config = sessionCalculator.getCalculationInfo();
            
            summary.put("activeSessions", activeSessions);
            summary.put("waitingUsers", waitingUsers);
            summary.put("maxSessions", config.calculatedMaxSessions());
            summary.put("podCount", config.currentPodCount());
            summary.put("utilization", config.calculatedMaxSessions() > 0 ? 
                       (activeSessions * 100.0) / config.calculatedMaxSessions() : 0.0);
            summary.put("timestamp", System.currentTimeMillis());
            
        } catch (Exception e) {
            logger.error("시스템 요약 정보 생성 중 오류", e);
            summary.put("error", e.getMessage());
        }
        
        return summary;
    }
}