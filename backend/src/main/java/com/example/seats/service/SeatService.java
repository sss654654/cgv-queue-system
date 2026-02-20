package com.example.seats.service;

import com.example.admission.dto.SeatLockResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;

/**
 * SeatService - 원자적 멀티좌석 선점 (all-or-nothing)
 *
 * 설계:
 * - seat_lock.lua로 다수 좌석을 원자적으로 선점
 * - 좌석 하나라도 이미 선점되어 있으면 전체 실패 (conflict)
 * - Redis key: seat:{movieId}:{theaterId}:{seatId} (Hash Tag on movieId, String SET NX EX 300)
 * - TTL 300초 (5분) 이내 결제 미완료 시 자동 해제
 * - 최대 4좌석 동시 선점 허용
 */
@Service
public class SeatService {

    private static final Logger logger = LoggerFactory.getLogger(SeatService.class);
    private static final int SEAT_LOCK_TTL_SECONDS = 300;
    private static final int MAX_SEATS_PER_REQUEST = 4;

    private final RedisTemplate<String, String> redisTemplate;
    private RedisScript<List> seatLockScript;

    public SeatService(RedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @PostConstruct
    private void loadScripts() {
        this.seatLockScript = RedisScript.of(
                new ClassPathResource("scripts/seat_lock.lua"), List.class);
        logger.info("seat_lock.lua 스크립트 로드 완료");
    }

    /**
     * 원자적 멀티좌석 선점 (all-or-nothing).
     *
     * @param movieId   영화 ID
     * @param theaterId 상영관 ID
     * @param seatIds   선점할 좌석 ID 목록 (최대 4개)
     * @param requestId 요청자 식별 ID (lock owner)
     * @return SeatLockResult - LOCKED 또는 CONFLICT
     */
    public SeatLockResult lockSeats(String movieId, String theaterId,
                                    List<String> seatIds, String requestId) {
        // 좌석 수 검증
        if (seatIds == null || seatIds.isEmpty()) {
            logger.warn("좌석 선점 요청 실패 - 좌석 목록 비어있음: requestId={}", requestId);
            return SeatLockResult.conflict(List.of());
        }

        if (seatIds.size() > MAX_SEATS_PER_REQUEST) {
            logger.warn("좌석 선점 요청 실패 - 최대 {}석 초과: requestId={}, 요청={}석",
                    MAX_SEATS_PER_REQUEST, requestId, seatIds.size());
            return SeatLockResult.conflict(List.of());
        }

        // Redis key 목록 생성: seat:{movieId}:{theaterId}:{seatId} (Hash Tag ensures same slot)
        List<String> keys = new ArrayList<>(seatIds.size());
        for (String seatId : seatIds) {
            keys.add("seat:{" + movieId + "}:" + theaterId + ":" + seatId);
        }

        try {
            @SuppressWarnings("unchecked")
            List<Object> result = redisTemplate.execute(
                    seatLockScript,
                    keys,
                    requestId,
                    String.valueOf(SEAT_LOCK_TTL_SECONDS)
            );

            if (result == null || result.isEmpty()) {
                logger.error("seat_lock.lua 실행 결과 null - requestId={}", requestId);
                return SeatLockResult.conflict(List.of());
            }

            long status = toLong(result.get(0));

            if (status == 1) {
                // 선점 성공
                long lockedUntil = System.currentTimeMillis() + (SEAT_LOCK_TTL_SECONDS * 1000L);
                logger.info("좌석 선점 성공 - movieId={}, theaterId={}, seats={}, requestId={}",
                        movieId, theaterId, seatIds, requestId);
                return SeatLockResult.locked(lockedUntil);
            } else {
                // 충돌 - result[1..N]은 충돌된 키 목록
                List<String> conflictKeys = new ArrayList<>();
                for (int i = 1; i < result.size(); i++) {
                    String fullKey = result.get(i).toString();
                    // seat:{movieId}:{theaterId}:{seatId} 에서 seatId 추출
                    String[] parts = fullKey.split(":");
                    if (parts.length >= 4) {
                        conflictKeys.add(parts[3]);
                    } else {
                        conflictKeys.add(fullKey);
                    }
                }
                logger.info("좌석 선점 충돌 - movieId={}, theaterId={}, conflicts={}, requestId={}",
                        movieId, theaterId, conflictKeys, requestId);
                return SeatLockResult.conflict(conflictKeys);
            }

        } catch (Exception e) {
            logger.error("좌석 선점 Redis 오류 - movieId={}, theaterId={}, requestId={}",
                    movieId, theaterId, requestId, e);
            return SeatLockResult.conflict(List.of());
        }
    }

    private long toLong(Object obj) {
        if (obj instanceof Long l) {
            return l;
        }
        if (obj instanceof Integer i) {
            return i.longValue();
        }
        return Long.parseLong(obj.toString());
    }
}
