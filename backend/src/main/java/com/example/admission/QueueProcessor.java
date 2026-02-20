// src/main/java/com/example/admission/QueueProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.service.QueueMetrics;
import com.example.admission.ws.WebSocketBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 대기열 처리기 (QueueProcessor).
 *
 * 2초마다(설정 가능) 실행되어 대기열의 사용자를 Active 세션으로 승격시킨다.
 *
 * 핵심 변경 사항 (2.2 리팩토링):
 * - 하드코딩된 movieId 목록 제거 -> admissionService.getActiveQueueMovieIds() 동적 조회
 * - parallelStream() 제거 -> 순차 루프 (Redis Lua 원자성 보장)
 * - SimpMessagingTemplate 직접 전송 -> WebSocketBroadcastService (Redis Pub/Sub) 사용
 * - PROCESSING_BATCH_SIZE를 환경변수로 외부화 (Dev=100, Prod=5000)
 *
 * 순차 처리 이유:
 * Redis Lua 스크립트는 서버 측에서 원자적으로 실행되지만, Java 측에서
 * parallelStream으로 동시에 여러 Lua 스크립트를 실행하면 Redis 단일 스레드 모델에서
 * 순서 보장이 안 되고 네트워크 부하만 증가한다. 순차 처리가 더 예측 가능하고 안전하다.
 */
@Component
public class QueueProcessor {

    private static final Logger logger = LoggerFactory.getLogger(QueueProcessor.class);

    private final AdmissionService admissionService;
    private final WebSocketBroadcastService broadcastService;
    private final LoadBalancingOptimizer loadBalancer;
    private final QueueMetrics queueMetrics;

    // Dev=100, Prod=5000 (vacantSlots가 자연 상한이므로 BATCH_SIZE는 안전 밸브 역할)
    @Value("${queue.processing-batch-size:100}")
    private int processingBatchSize;

    public QueueProcessor(AdmissionService admissionService,
                          WebSocketBroadcastService broadcastService,
                          LoadBalancingOptimizer loadBalancer,
                          QueueMetrics queueMetrics) {
        this.admissionService = admissionService;
        this.broadcastService = broadcastService;
        this.loadBalancer = loadBalancer;
        this.queueMetrics = queueMetrics;
    }

    /**
     * 주기적 대기열 처리 (기본 2초 간격, 환경변수로 조정 가능).
     * 모든 활성 영화에 대해 빈 슬롯만큼 대기자를 승격시킨다.
     */
    @Scheduled(fixedDelayString = "${queue.process-interval:2000}")
    public void processAllQueues() {
        try {
            // 동적으로 활성 영화 ID 조회 (하드코딩 제거)
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();

            if (movieIds.isEmpty()) {
                logger.debug("처리할 활성 대기열 없음");
                return;
            }

            // 순차 처리: Redis Lua 원자성 보장, parallelStream 제거
            for (String movieId : movieIds) {
                try {
                    // Pod 간 작업 분배 (LoadBalancingOptimizer)
                    if (loadBalancer.shouldProcessMovie(movieId)) {
                        processMovieQueue("movie", movieId);
                    }
                } catch (Exception e) {
                    logger.warn("영화 대기열 처리 실패: {}", movieId, e);
                }
            }

        } catch (Exception e) {
            logger.error("대기열 처리 중 전체 오류 발생", e);
        }
    }

    /**
     * 단일 영화의 대기열을 처리한다.
     * 1) 빈 슬롯 수 계산
     * 2) 대기자를 Active로 승격 (Lua 원자적 배치)
     * 3) 승격된 사용자에게 Redis Pub/Sub로 입장 알림 전송
     */
    private void processMovieQueue(String type, String movieId) {
        try {
            long vacantSlots = admissionService.getVacantSlots(type, movieId);
            long waitingCount = admissionService.getTotalWaitingCount(type, movieId);

            logger.debug("영화 {} 처리: 빈자리={}, 대기자={}", movieId, vacantSlots, waitingCount);

            if (vacantSlots > 0 && waitingCount > 0) {
                long admitCount = Math.min(vacantSlots,
                        Math.min(waitingCount, processingBatchSize));

                List<String> admittedUsers = admissionService.admitNextUsers(
                        type, movieId, admitCount);

                if (!admittedUsers.isEmpty()) {
                    logger.info("영화 {} - {}명 입장 처리 완료", movieId, admittedUsers.size());

                    // Prometheus Counter 증가 (승격 건수)
                    for (String requestId : admittedUsers) {
                        queueMetrics.incrementProcessed(movieId);
                        // 승격된 사용자에게 Redis Pub/Sub로 입장 알림 전송
                        broadcastService.notifyAdmission(requestId, movieId);
                    }
                }
            }

        } catch (Exception e) {
            logger.error("대기열 처리 중 오류: {} {}", type, movieId, e);
        }
    }

    /**
     * 수동 대기열 처리 트리거 (관리/디버깅용).
     */
    public void forceProcessQueue(String type, String movieId) {
        logger.info("수동 대기열 처리 실행: {} {}", type, movieId);
        try {
            processMovieQueue(type, movieId);
        } catch (Exception e) {
            logger.error("수동 대기열 처리 실패: {} {}", type, movieId, e);
            throw new RuntimeException("수동 처리 실패", e);
        }
    }

    /**
     * 시스템 상태 체크 (관리 API용).
     */
    public Map<String, Object> getProcessorStatus() {
        try {
            Set<String> activeMovies = admissionService.getActiveQueueMovieIds();

            long totalWaiting = 0;
            long totalActive = 0;
            for (String movieId : activeMovies) {
                try {
                    totalWaiting += admissionService.getTotalWaitingCount("movie", movieId);
                    totalActive += admissionService.getTotalActiveCount("movie", movieId);
                } catch (Exception e) {
                    logger.warn("영화 {} 통계 조회 실패", movieId, e);
                }
            }

            return Map.of(
                "processingBatchSize", processingBatchSize,
                "activeMovies", activeMovies.size(),
                "totalWaitingUsers", totalWaiting,
                "totalActiveUsers", totalActive,
                "lastProcessedAt", System.currentTimeMillis(),
                "status", "HEALTHY"
            );

        } catch (Exception e) {
            logger.error("프로세서 상태 조회 실패", e);
            return Map.of(
                "status", "ERROR",
                "error", e.getMessage(),
                "timestamp", System.currentTimeMillis()
            );
        }
    }
}
