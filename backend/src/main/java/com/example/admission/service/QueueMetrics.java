package com.example.admission.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QueueMetrics - Prometheus 메트릭 via Micrometer
 *
 * KEDA ScaledObject가 cgv_queue_waiting_count{movieId}를 trigger로 사용한다.
 * threshold=1000 (values-prod.yaml) 초과 시 Pod 스케일아웃.
 *
 * Gauge (현재 상태):
 *   cgv_queue_waiting_count{movieId}     - 대기열 크기 (KEDA trigger)
 *   cgv_active_sessions_count{movieId}   - 활성 세션 수
 *
 * Counter (누적):
 *   cgv_admission_processed_total{movieId}   - 입장 처리 건수
 *   cgv_admission_completed_total{movieId}   - 예매 완료 건수
 *   cgv_tickets_sold_total{movieId,theaterId} - 판매 티켓 수
 *   cgv_seat_lock_conflicts{movieId}         - 좌석 선점 충돌 수
 *
 * Timer:
 *   cgv_admission_duration_seconds{movieId}  - 입장 처리 소요 시간
 */
@Component
public class QueueMetrics {

    private final MeterRegistry registry;

    // Gauge 값을 보관하는 AtomicLong map (movieId -> value)
    private final ConcurrentHashMap<String, AtomicLong> waitingGauges = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> activeGauges = new ConcurrentHashMap<>();

    public QueueMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    // ========== Gauge Updates ==========

    /**
     * 대기열 크기 갱신. KEDA가 이 메트릭을 폴링하여 스케일링 결정을 내린다.
     */
    public void updateWaitingCount(String movieId, long count) {
        AtomicLong gauge = waitingGauges.computeIfAbsent(movieId, id -> {
            AtomicLong value = new AtomicLong(0);
            registry.gauge("cgv_queue_waiting_count", io.micrometer.core.instrument.Tags.of("movieId", id), value);
            return value;
        });
        gauge.set(count);
    }

    /**
     * 활성 세션 수 갱신.
     */
    public void updateActiveCount(String movieId, long count) {
        AtomicLong gauge = activeGauges.computeIfAbsent(movieId, id -> {
            AtomicLong value = new AtomicLong(0);
            registry.gauge("cgv_active_sessions_count", io.micrometer.core.instrument.Tags.of("movieId", id), value);
            return value;
        });
        gauge.set(count);
    }

    // ========== Counter Increments ==========

    /**
     * 대기열 -> 활성 세션 승격 시 호출
     */
    public void incrementProcessed(String movieId) {
        Counter.builder("cgv_admission_processed_total")
                .tag("movieId", movieId)
                .register(registry)
                .increment();
    }

    /**
     * 예매 완료 (booking_complete) 시 호출
     */
    public void incrementCompleted(String movieId) {
        Counter.builder("cgv_admission_completed_total")
                .tag("movieId", movieId)
                .register(registry)
                .increment();
    }

    /**
     * 티켓 판매 (상영관별) 시 호출
     */
    public void incrementTicketsSold(String movieId, String theaterId) {
        Counter.builder("cgv_tickets_sold_total")
                .tag("movieId", movieId)
                .tag("theaterId", theaterId)
                .register(registry)
                .increment();
    }

    /**
     * 좌석 선점 충돌 (이미 다른 사용자가 선점) 시 호출
     */
    public void incrementSeatConflict(String movieId) {
        Counter.builder("cgv_seat_lock_conflicts")
                .tag("movieId", movieId)
                .register(registry)
                .increment();
    }

    // ========== Timer ==========

    /**
     * 입장 처리 소요 시간 기록 (대기열 등록 -> 활성 세션 전환)
     */
    public void recordAdmissionDuration(String movieId, long durationMs) {
        Timer.builder("cgv_admission_duration_seconds")
                .tag("movieId", movieId)
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }
}
