// src/main/java/com/example/admission/service/DynamicSessionCalculator.java
package com.example.admission.service;

import com.example.pod.service.PodDiscoveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Pod 수에 따른 동적 세션 수 계산기.
 *
 * 핵심 공식: maxActiveSessions = min(podCount x baseSessionsPerPod, maxTotalSessions)
 *
 * 환경별 기본값:
 * - Dev:  2 Pod x 500 = 1,000 (maxTotalSessions cap 5,000)
 * - Prod: 10 Pod x 500 = 5,000 (maxTotalSessions cap 5,000)
 *
 * @Value 기본값은 application.yml에서 환경변수가 없을 때의 폴백이며,
 * EKS 환경에서는 values.yaml / values-prod.yaml의 환경변수로 주입한다.
 */
@Service
public class DynamicSessionCalculator {

    private static final Logger logger = LoggerFactory.getLogger(DynamicSessionCalculator.class);

    private final PodDiscoveryService podDiscoveryService;

    @Value("${admission.enable-dynamic-scaling:true}")
    private boolean dynamicScalingEnabled;

    // Pod당 활성 세션 수: Prod에서 10 Pod x 500 = 5,000
    @Value("${admission.base-sessions-per-pod:500}")
    private int baseSessionsPerPod;

    // 최대 총 세션 상한: 10 Pod x 500 = 5,000
    @Value("${admission.max-total-sessions:5000}")
    private int maxTotalSessionsLimit;

    // K8s API 호출 실패 시 사용할 폴백 Pod 수
    @Value("${admission.fallback-pod-count:2}")
    private int fallbackPodCount;

    public DynamicSessionCalculator(PodDiscoveryService podDiscoveryService) {
        this.podDiscoveryService = podDiscoveryService;
    }

    /**
     * 현재 Pod 수와 설정값을 기반으로 최대 활성 세션 수를 계산한다.
     *
     * 계산: min(podCount x baseSessionsPerPod, maxTotalSessionsLimit)
     *
     * 예시:
     *  - Dev: 2 Pod x 500 = 1,000 (cap 5,000 -> 결과 1,000)
     *  - Prod (평상시): 2 Pod x 500 = 1,000 (KEDA 스케일 전)
     *  - Prod (피크): 10 Pod x 500 = 5,000 (KEDA 스케일 후, cap 도달)
     */
    public long calculateMaxActiveSessions() {
        int currentPodCount = getPodCount();
        long calculatedSessions = (long) currentPodCount * baseSessionsPerPod;
        long finalMaxSessions = Math.min(calculatedSessions, maxTotalSessionsLimit);

        logger.info("세션 계산: Pod {}개 x {} = {} (상한: {}, 최종: {})",
                currentPodCount, baseSessionsPerPod, calculatedSessions,
                maxTotalSessionsLimit, finalMaxSessions);

        return finalMaxSessions;
    }

    private int getPodCount() {
        if (!dynamicScalingEnabled) {
            logger.info("동적 스케일링 비활성화. Fallback Pod 수({})를 사용합니다.",
                    fallbackPodCount);
            return fallbackPodCount;
        }

        try {
            int discoveredPods = podDiscoveryService.getPodCount();
            if (discoveredPods <= 0) {
                logger.error("Pod 수가 0 이하로 감지됨 ({}). Fallback Pod 수({})를 사용합니다.",
                        discoveredPods, fallbackPodCount);
                return fallbackPodCount;
            }
            logger.info("Kubernetes에서 Pod 수 확인: {}개", discoveredPods);
            return discoveredPods;
        } catch (Exception e) {
            logger.error("Kubernetes API 호출 실패. Fallback Pod 수({})를 사용합니다. 에러: {}",
                    fallbackPodCount, e.getMessage());
            return fallbackPodCount;
        }
    }

    /**
     * 현재 세션 계산 상태 정보를 반환한다 (관리 API용).
     */
    public SessionCalculationInfo getCalculationInfo() {
        boolean k8sAvailable = podDiscoveryService.isKubernetesClientAvailable();
        int currentPodCount = k8sAvailable && dynamicScalingEnabled
                ? podDiscoveryService.getPodCount() : fallbackPodCount;
        if (currentPodCount <= 0) currentPodCount = fallbackPodCount;

        long calculated = (long) currentPodCount * baseSessionsPerPod;
        long finalMax = Math.min(calculated, maxTotalSessionsLimit);

        return new SessionCalculationInfo(
                dynamicScalingEnabled,
                baseSessionsPerPod,
                maxTotalSessionsLimit,
                fallbackPodCount,
                k8sAvailable,
                currentPodCount,
                finalMax
        );
    }

    public record SessionCalculationInfo(
            boolean dynamicScalingEnabled,
            int baseSessionsPerPod,           // 500 (Dev/Prod 동일)
            int maxTotalSessionsLimit,        // 5000 (10 Pod x 500)
            int fallbackPodCount,             // 2
            boolean kubernetesAvailable,
            int currentPodCount,              // 실제 또는 Fallback
            long calculatedMaxSessions        // min(podCount x 500, 5000)
    ) {

        /**
         * 예상 대기 시간 계산 헬퍼.
         * QueueProcessor가 2초마다 빈 슬롯만큼 승격하므로,
         * 대기 순위를 기반으로 대략적인 대기 시간을 추정한다.
         *
         * @param queuePosition 대기열에서의 순위 (1부터 시작)
         * @return 예상 대기 시간 (초)
         */
        public int calculateEstimatedWaitTimeSeconds(long queuePosition) {
            if (queuePosition <= 0) return 0;

            // 2초마다 baseSessionsPerPod만큼 승격된다고 가정
            // 내 앞에 있는 사람 수 / 배치 크기 x 주기
            long batchesBeforeMe = (queuePosition - 1) / baseSessionsPerPod;
            int processingIntervalSeconds = 2;

            return (int) (batchesBeforeMe * processingIntervalSeconds);
        }
    }
}
