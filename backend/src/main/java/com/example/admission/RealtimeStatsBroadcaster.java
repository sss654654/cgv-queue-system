// src/main/java/com/example/admission/RealtimeStatsBroadcaster.java
package com.example.admission;

import com.example.admission.service.AdmissionService;
import com.example.admission.service.QueueMetrics;
import com.example.admission.ws.WebSocketBroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 실시간 대기열 통계 브로드캐스터 (broadcast-only 아키텍처).
 *
 * 1초마다 각 활성 영화의 대기열 통계를 Redis Pub/Sub로 발행한다.
 * 모든 대기 사용자에게 동일한 통계를 전송하여 클라이언트가 자체적으로 순위를 계산한다.
 *
 * broadcast-only 전환 근거 (1.0 섹션 7):
 * - 개별 순위 전송: 10만 명 x 10 Pod = 100만 메시지/초
 * - 브로드캐스트: 1 x 10 Pod = 10 메시지/초
 * - 대기열이 Sorted Set(FIFO)이므로 내 앞 사람이 반드시 먼저 나간다.
 * - 클라이언트가 초기 순번 + totalProcessed로 현재 순번을 정확히 계산 가능.
 *
 * 이전 코드에서 제거된 항목:
 * - updateIndividualRanks() (2초마다 개별 순위 전송) -> 삭제
 * - forceRankSync() (5초마다 전체 순위 강제 동기화) -> 삭제
 * - previousRanks 캐시 -> 삭제
 * - getAllUserRanks() 호출 -> 삭제
 *
 * 클라이언트 측 순위 계산:
 * myCurrentRank = myInitialRank - data.totalProcessed
 */
@Component
public class RealtimeStatsBroadcaster {

    private static final Logger logger = LoggerFactory.getLogger(RealtimeStatsBroadcaster.class);

    private final AdmissionService admissionService;
    private final WebSocketBroadcastService broadcastService;
    private final QueueMetrics queueMetrics;

    public RealtimeStatsBroadcaster(AdmissionService admissionService,
                                    WebSocketBroadcastService broadcastService,
                                    QueueMetrics queueMetrics) {
        this.admissionService = admissionService;
        this.broadcastService = broadcastService;
        this.queueMetrics = queueMetrics;
    }

    /**
     * 1초마다 전체 통계 브로드캐스트.
     * 각 활성 영화에 대해 waitingCount, activeCount, totalProcessed를 발행한다.
     * 개별 순위(ZRANK)를 계산하지 않으므로 Redis 부하가 최소화된다.
     */
    @Scheduled(fixedRate = 1000)
    public void broadcastRealtimeStats() {
        try {
            Set<String> movieIds = admissionService.getActiveQueueMovieIds();

            if (movieIds.isEmpty()) {
                return;
            }

            for (String movieId : movieIds) {
                try {
                    long waitingCount = admissionService.getTotalWaitingCount("movie", movieId);
                    long activeCount = admissionService.getTotalActiveCount("movie", movieId);
                    long totalProcessed = admissionService.getTotalProcessedCount("movie", movieId);

                    // Prometheus Gauge 갱신 (KEDA trigger용)
                    queueMetrics.updateWaitingCount(movieId, waitingCount);
                    queueMetrics.updateActiveCount(movieId, activeCount);

                    // 대기자 또는 활성 사용자가 있을 때만 브로드캐스트
                    if (waitingCount > 0 || activeCount > 0) {
                        broadcastService.broadcastQueueStats(
                                movieId, waitingCount, activeCount, totalProcessed);

                        logger.debug("[실시간 통계] movieId={}, 대기={}명, 활성={}명, 누적처리={}명",
                                movieId, waitingCount, activeCount, totalProcessed);
                    }
                } catch (Exception e) {
                    logger.error("영화 {} 통계 브로드캐스트 실패", movieId, e);
                }
            }

        } catch (Exception e) {
            logger.error("실시간 통계 브로드캐스트 전체 실패", e);
        }
    }
}
