// src/main/java/com/example/admission/SessionTimeoutProcessor.java
package com.example.admission;

import com.example.admission.service.AdmissionMetricsService;
import com.example.admission.service.AdmissionService;
import com.example.admission.service.LoadBalancingOptimizer;
import com.example.admission.ws.WebSocketBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 만료된 Active 세션을 정리하는 프로세서.
 *
 * Active 세션 타임아웃(Dev=300초, Prod=600초)을 초과한 세션을 찾아
 * Redis에서 제거하고, 사용자에게 타임아웃 알림을 전송한다.
 * 빈자리가 생기면 QueueProcessor가 다음 대기자를 승격시킨다.
 *
 * 핵심 변경 사항 (2.2 리팩토링):
 * - WebSocketUpdateService -> WebSocketBroadcastService (Redis Pub/Sub)
 * - 하드코딩 movieId 제거 -> admissionService.getActiveQueueMovieIds() 동적 조회
 * - SESSION_TIMEOUT: admission.session-timeout-seconds (Dev=300, Prod=600)
 *
 * 2종 타임아웃 구분:
 * - 좌석 선점 TTL: seat:{movieId}:{theaterId}:{seatId} -> Redis EX 자동 만료 (300초)
 * - Active 세션 타임아웃: sessions:{movieId}:active -> 이 프로세서가 처리 (Dev 300/Prod 600)
 */
@Component
public class SessionTimeoutProcessor {

    private static final Logger logger = LoggerFactory.getLogger(SessionTimeoutProcessor.class);

    private final AdmissionService admissionService;
    private final WebSocketBroadcastService broadcastService;
    private final AdmissionMetricsService metricsService;
    private final LoadBalancingOptimizer loadBalancer;

    public SessionTimeoutProcessor(AdmissionService admissionService,
                                   WebSocketBroadcastService broadcastService,
                                   AdmissionMetricsService metricsService,
                                   LoadBalancingOptimizer loadBalancer) {
        this.admissionService = admissionService;
        this.broadcastService = broadcastService;
        this.metricsService = metricsService;
        this.loadBalancer = loadBalancer;
    }

    /**
     * 10초마다 만료 세션을 정리한다.
     * 동적으로 활성 영화 ID를 조회하여 하드코딩 없이 처리한다.
     */
    @Scheduled(fixedDelayString = "${admission.session-cleanup-interval:10000}")
    public void processExpiredSessions() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();
            if (movieIds == null || movieIds.isEmpty()) {
                return;
            }

            for (String movieId : movieIds) {
                // Pod 간 작업 분배 (LoadBalancingOptimizer)
                if (loadBalancer.shouldProcessMovie(movieId)) {
                    processMovieExpiredSessions("movie", movieId);
                }
            }
        } catch (Exception e) {
            logger.error("만료 세션 정리 중 오류 발생", e);
        }
    }

    private void processMovieExpiredSessions(String type, String movieId) {
        try {
            Set<String> expiredMembers = admissionService.findExpiredActiveSessions(type, movieId);

            if (expiredMembers.isEmpty()) {
                return;
            }

            logger.warn("[{}] 타임아웃된 활성 세션 {}개를 정리합니다.", movieId, expiredMembers.size());
            admissionService.removeActiveSessions(type, movieId, expiredMembers);

            for (String requestId : expiredMembers) {
                // Redis Pub/Sub로 타임아웃 알림 전송 (모든 Pod에 브로드캐스트)
                broadcastService.notifyTimeout(requestId, movieId);
                metricsService.recordTimeout(movieId, 1);
            }
        } catch (Exception e) {
            logger.error("[{}] 만료 세션 처리 중 오류", movieId, e);
        }
    }
}
