// src/main/java/com/example/admission/service/AdmissionService.java
package com.example.admission.service;

import com.example.admission.dto.EnterResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;
import io.lettuce.core.RedisCommandExecutionException;

import java.util.*;

@Service
public class AdmissionService {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionService.class);

    // 동적 영화 ID 추적을 위한 Redis Set 키
    private static final String ACTIVE_MOVIES = "active_movies";
    private static final String WAITING_MOVIES = "waiting_movies";

    private final RedisTemplate<String, String> redisTemplate;
    private final SetOperations<String, String> setOps;
    private final ZSetOperations<String, String> zSetOps;
    private final DynamicSessionCalculator sessionCalculator;

    // SESSION_TIMEOUT: Dev=300초, Prod=600초 (values.yaml에서 환경변수로 주입)
    // @Value 기본값 300은 Dev 환경 기본값. Prod는 values-prod.yaml에서 600으로 덮어씀.
    @Value("${admission.session-timeout-seconds:300}")
    private long sessionTimeoutSeconds;

    public AdmissionService(RedisTemplate<String, String> redisTemplate,
                            DynamicSessionCalculator sessionCalculator) {
        this.redisTemplate = redisTemplate;
        this.setOps = redisTemplate.opsForSet();
        this.zSetOps = redisTemplate.opsForZSet();
        this.sessionCalculator = sessionCalculator;
    }

    // --- Redis 키 생성 (Hash Tag로 CROSSSLOT 방지) ---

    // Hash Tag {movieId}를 사용하여 같은 영화의 active/waiting 키가
    // 동일 슬롯에 배치되도록 한다. Lua 스크립트에서 두 키를 원자적으로 접근하기 위한 전제조건.
    // Non-Cluster 모드에서는 불필요하지만, 향후 Cluster 전환 시 호환성 보장.
    private String activeSessionsKey(String type, String movieId) {
        return "sessions:{" + movieId + "}:active";
    }

    private String waitingQueueKey(String type, String movieId) {
        return "sessions:{" + movieId + "}:waiting";
    }

    // 누적 입장 카운터 키 (broadcast-only 아키텍처에서 클라이언트 순위 계산용)
    private String processedCountKey(String movieId) {
        return "processed:{" + movieId + "}";
    }

    // --- WRONGTYPE 방어 로직 ---

    private void ensureKeyType(String key, String expectedType) {
        try {
            String actualType = redisTemplate.type(key).name();
            if (!"NONE".equals(actualType) && !expectedType.equals(actualType)) {
                logger.warn("키 타입 불일치 감지 (예상: {}, 실제: {}). 키 삭제 후 재생성: {}",
                        expectedType, actualType, key);
                redisTemplate.delete(key);
            }
        } catch (Exception e) {
            logger.error("키 타입 확인 중 오류, 키 삭제 후 재생성: {}", key, e);
            redisTemplate.delete(key);
        }
    }

    private boolean isWrongTypeError(Exception e) {
        if (e instanceof RedisSystemException) {
            Throwable cause = e.getCause();
            if (cause instanceof RedisCommandExecutionException) {
                String message = ((RedisCommandExecutionException) cause).getMessage();
                return message.startsWith("WRONGTYPE") || message.contains("CROSSSLOT");
            }
        }
        return false;
    }

    // --- 대기열 입장 (3 params: type, movieId, requestId) ---

    /**
     * 대기열 입장 처리.
     * Lua 스크립트로 active 세션 수 확인 + 즉시 입장 또는 대기열 등록을 원자적으로 처리한다.
     * Hash Tag 키({movieId})로 CROSSSLOT 오류를 방지한다.
     *
     * @param type     컨텐츠 유형 ("movie")
     * @param movieId  영화 ID
     * @param requestId 사용자 요청 ID (고유 식별자)
     * @return EnterResponse with ADMITTED or WAITING status
     */
    public EnterResponse enter(String type, String movieId, String requestId) {
        String activeKey = activeSessionsKey(type, movieId);
        String waitingKey = waitingQueueKey(type, movieId);

        // 키 타입 사전 검증 (WRONGTYPE 오류 방지)
        ensureKeyType(activeKey, "ZSET");
        ensureKeyType(waitingKey, "ZSET");

        long now = System.currentTimeMillis();
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();

        // Lua 스크립트: active 세션 수 확인 후 즉시 입장 또는 대기열 등록
        // KEYS[1] = activeKey, KEYS[2] = waitingKey (Hash Tag로 같은 슬롯)
        String luaScript = """
            local activeKey = KEYS[1]
            local waitingKey = KEYS[2]
            local maxSessions = tonumber(ARGV[1])
            local member = ARGV[2]
            local now = tonumber(ARGV[3])

            -- 이미 활성 세션에 있는지 확인 (중복 입장 방지)
            local existingScore = redis.call('ZSCORE', activeKey, member)
            if existingScore then
                return {1, 'ALREADY_ACTIVE', redis.call('ZCARD', activeKey)}
            end

            -- 이미 대기열에 있는지 확인 (중복 등록 방지)
            local waitingScore = redis.call('ZSCORE', waitingKey, member)
            if waitingScore then
                local rank = redis.call('ZRANK', waitingKey, member)
                local totalWaiting = redis.call('ZCARD', waitingKey)
                return {2, 'ALREADY_WAITING', rank + 1, totalWaiting}
            end

            -- 현재 활성 세션 수 확인
            local activeCount = redis.call('ZCARD', activeKey)

            if activeCount < maxSessions then
                -- 즉시 활성 세션으로 추가
                redis.call('ZADD', activeKey, now, member)
                return {1, 'ADMITTED', activeCount + 1}
            else
                -- 대기열에 추가
                redis.call('ZADD', waitingKey, now, member)
                local rank = redis.call('ZRANK', waitingKey, member)
                local totalWaiting = redis.call('ZCARD', waitingKey)
                return {2, 'WAITING', rank + 1, totalWaiting}
            end
        """;

        try {
            RedisScript<List> script = RedisScript.of(luaScript, List.class);
            List<Object> result = redisTemplate.execute(script,
                    Arrays.asList(activeKey, waitingKey),
                    String.valueOf(maxSessions), requestId, String.valueOf(now));

            if (result == null || result.isEmpty()) {
                throw new RuntimeException("Lua 스크립트 실행 결과가 비어 있음");
            }

            int statusCode = Integer.parseInt(result.get(0).toString());

            // 영화를 활성 목록에 추가 (동적 movieId 추적)
            setOps.add(ACTIVE_MOVIES, movieId);
            if (statusCode == 2) {
                setOps.add(WAITING_MOVIES, movieId);
            }

            if (statusCode == 1) {
                // 즉시 입장 (ADMITTED)
                logger.info("즉시 입장 허가 - requestId: {}..., 현재 활성: {}/{}",
                        requestId.substring(0, Math.min(8, requestId.length())),
                        result.get(2), maxSessions);
                return new EnterResponse(EnterResponse.Status.ADMITTED,
                        "즉시 입장", requestId, null, null);
            } else {
                // 대기열 등록 (WAITING)
                Long myRank = Long.parseLong(result.get(2).toString());
                Long totalWaiting = Long.parseLong(result.get(3).toString());
                logger.info("대기열 등록 완료 - rank: {}/{}, requestId: {}...",
                        myRank, totalWaiting,
                        requestId.substring(0, Math.min(8, requestId.length())));
                return new EnterResponse(EnterResponse.Status.WAITING,
                        "대기열 등록", requestId, myRank, totalWaiting);
            }
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.error("Redis 스크립트 실행 오류. 키 정리 후 재시도 필요: {}", e.getMessage());
                redisTemplate.delete(activeKey);
                redisTemplate.delete(waitingKey);
                throw new RuntimeException(
                        "Redis 오류로 인한 입장 처리 실패. 잠시 후 다시 시도해주세요.", e);
            }
            throw e;
        }
    }

    // --- 예매 완료 (Active 세션에서 제거) ---

    /**
     * 예매 완료 처리. Active 세션에서 사용자를 제거하여 슬롯을 반환한다.
     * QueueProcessor가 다음 대기자를 승격시킨다.
     * ZREM은 멱등성을 보장하므로 중복 호출 시 안전하다 (이미 제거된 멤버는 0 반환).
     *
     * @return true if the user was found and removed, false if not present
     */
    public boolean completeAdmission(String type, String movieId, String requestId) {
        String activeKey = activeSessionsKey(type, movieId);
        try {
            ensureKeyType(activeKey, "ZSET");
            Long removed = zSetOps.remove(activeKey, requestId);
            if (removed != null && removed > 0) {
                logger.info("예매 완료 - Active 세션에서 제거: movieId={}, requestId={}...",
                        movieId, requestId.substring(0, Math.min(8, requestId.length())));
                return true;
            }
            logger.warn("예매 완료 시도 - Active 세션에 없는 사용자: movieId={}, requestId={}...",
                    movieId, requestId.substring(0, Math.min(8, requestId.length())));
            return false;
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.error("예매 완료 중 WRONGTYPE 오류. 키 삭제: {}", activeKey);
                redisTemplate.delete(activeKey);
            }
            return false;
        }
    }

    // --- 대기자 승격 (Lua 원자적 배치 처리) ---

    /**
     * 대기열에서 count명을 꺼내 Active 세션으로 승격한다.
     * Lua 스크립트로 ZRANGE + ZREM + ZADD를 원자적으로 처리.
     * 승격된 사용자 수만큼 processedCount를 증가시킨다.
     */
    public List<String> admitNextUsers(String type, String movieId, long count) {
        String activeKey = activeSessionsKey(type, movieId);
        String waitingKey = waitingQueueKey(type, movieId);
        String countKey = processedCountKey(movieId);

        try {
            ensureKeyType(activeKey, "ZSET");
            ensureKeyType(waitingKey, "ZSET");

            String luaScript = """
                local waitingKey = KEYS[1]
                local activeKey = KEYS[2]
                local countKey = KEYS[3]
                local count = tonumber(ARGV[1])
                local now = tonumber(ARGV[2])

                local waitingUsers = redis.call('ZRANGE', waitingKey, 0, count - 1)
                local admitted = {}

                for i = 1, #waitingUsers do
                    local user = waitingUsers[i]
                    redis.call('ZREM', waitingKey, user)
                    redis.call('ZADD', activeKey, now, user)
                    table.insert(admitted, user)
                end

                -- 승격 수만큼 누적 카운터 증가 (broadcast-only 순위 계산용)
                if #admitted > 0 then
                    redis.call('INCRBY', countKey, #admitted)
                end

                return admitted
            """;

            long now = System.currentTimeMillis();
            RedisScript<List> script = RedisScript.of(luaScript, List.class);
            List<String> admitted = redisTemplate.execute(script,
                    Arrays.asList(waitingKey, activeKey, countKey),
                    String.valueOf(count), String.valueOf(now));

            if (admitted != null && !admitted.isEmpty()) {
                logger.info("{}명을 대기열에서 활성 세션으로 승격 (movieId={})",
                        admitted.size(), movieId);

                // 대기열이 비면 waiting_movies에서 제거
                Long remainingWaiting = zSetOps.zCard(waitingKey);
                if (remainingWaiting != null && remainingWaiting == 0) {
                    setOps.remove(WAITING_MOVIES, movieId);
                }

                return admitted;
            }

            return Collections.emptyList();

        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.error("사용자 승격 중 Redis 오류. 키 정리: {}", e.getMessage());
                redisTemplate.delete(activeKey);
                redisTemplate.delete(waitingKey);
            }
            logger.error("사용자 승격 실패", e);
            return Collections.emptyList();
        }
    }

    // --- 조회 메서드 ---

    public long getTotalActiveCount(String type, String movieId) {
        String key = activeSessionsKey(type, movieId);
        try {
            ensureKeyType(key, "ZSET");
            return Optional.ofNullable(zSetOps.zCard(key)).orElse(0L);
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("WRONGTYPE 오류 감지. 키 삭제 후 재시도");
                redisTemplate.delete(key);
                return 0L;
            }
            logger.error("Redis 조회 실패", e);
            return 0L;
        }
    }

    public long getTotalWaitingCount(String type, String movieId) {
        String key = waitingQueueKey(type, movieId);
        try {
            ensureKeyType(key, "ZSET");
            return Optional.ofNullable(zSetOps.zCard(key)).orElse(0L);
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("WRONGTYPE 오류 감지. 키 삭제 후 재시도");
                redisTemplate.delete(key);
                return 0L;
            }
            logger.error("Redis 조회 실패", e);
            return 0L;
        }
    }

    /**
     * 누적 입장 처리 수를 조회한다.
     * broadcast-only 아키텍처에서 클라이언트가 자체 순위를 계산하기 위한 단조 증가 카운터.
     * 키: processed:{movieId}
     */
    public long getTotalProcessedCount(String type, String movieId) {
        try {
            String key = processedCountKey(movieId);
            String value = redisTemplate.opsForValue().get(key);
            return value != null ? Long.parseLong(value) : 0L;
        } catch (Exception e) {
            logger.error("누적 처리 수 조회 실패: movieId={}", movieId, e);
            return 0L;
        }
    }

    public long getVacantSlots(String type, String movieId) {
        long maxSessions = sessionCalculator.calculateMaxActiveSessions();
        long currentSessions = getTotalActiveCount(type, movieId);
        return Math.max(0, maxSessions - currentSessions);
    }

    // --- 동적 영화 ID 조회 ---

    /**
     * 현재 활성화된(대기열 또는 활성 세션이 있는) 모든 영화 ID를 동적으로 조회한다.
     * Redis Set인 active_movies와 waiting_movies의 합집합을 반환.
     * QueueProcessor, SessionTimeoutProcessor, RealtimeStatsBroadcaster에서
     * 하드코딩된 영화 ID 목록 대신 이 메서드를 사용한다.
     */
    public Set<String> getActiveQueueMovieIds() {
        Set<String> activeMovies = setOps.members(ACTIVE_MOVIES);
        Set<String> waitingMovies = setOps.members(WAITING_MOVIES);
        Set<String> allMovies = new HashSet<>();
        if (activeMovies != null) allMovies.addAll(activeMovies);
        if (waitingMovies != null) allMovies.addAll(waitingMovies);
        return allMovies;
    }

    // --- 퇴장 ---

    public void leave(String type, String movieId, String requestId) {
        try {
            zSetOps.remove(activeSessionsKey(type, movieId), requestId);
            zSetOps.remove(waitingQueueKey(type, movieId), requestId);
            logger.info("사용자 퇴장 - requestId: {}...",
                    requestId.substring(0, Math.min(8, requestId.length())));
        } catch (Exception e) {
            logger.warn("퇴장 처리 중 오류 (무시)", e);
        }
    }

    // --- 활성 세션 확인 ---

    public boolean isUserInActiveSession(String type, String movieId, String requestId) {
        try {
            String key = activeSessionsKey(type, movieId);
            ensureKeyType(key, "ZSET");
            return zSetOps.score(key, requestId) != null;
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("활성 세션 확인 중 Redis 오류. 키 삭제");
                redisTemplate.delete(activeSessionsKey(type, movieId));
            }
            return false;
        }
    }

    // --- 사용자 순위 조회 ---

    public Long getUserRank(String type, String movieId, String requestId) {
        try {
            String waitingKey = waitingQueueKey(type, movieId);
            ensureKeyType(waitingKey, "ZSET");
            Long rank = zSetOps.rank(waitingKey, requestId);
            return (rank != null) ? rank + 1 : null;
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("순위 조회 중 Redis 오류. 키 삭제");
                redisTemplate.delete(waitingQueueKey(type, movieId));
            }
            return null;
        }
    }

    // --- 만료 세션 처리 ---

    public Set<String> findExpiredActiveSessions(String type, String movieId) {
        String key = activeSessionsKey(type, movieId);
        try {
            ensureKeyType(key, "ZSET");
            long expirationThreshold = System.currentTimeMillis() - (sessionTimeoutSeconds * 1000);
            return zSetOps.rangeByScore(key, 0, expirationThreshold);
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                logger.warn("만료 세션 조회 중 Redis 오류. 키 삭제");
                redisTemplate.delete(key);
            }
            return Collections.emptySet();
        }
    }

    public void removeActiveSessions(String type, String movieId, Set<String> expiredMembers) {
        if (expiredMembers != null && !expiredMembers.isEmpty()) {
            String key = activeSessionsKey(type, movieId);
            try {
                zSetOps.remove(key, expiredMembers.toArray(new String[0]));
                logger.info("{}개 만료 세션 정리 (movieId={})", expiredMembers.size(), movieId);
            } catch (RedisSystemException e) {
                if (isWrongTypeError(e)) {
                    logger.warn("세션 정리 중 Redis 오류. 키 삭제");
                    redisTemplate.delete(key);
                }
            }
        }
    }

    /**
     * Active 세션에서 특정 사용자를 제거한다.
     * completeAdmission의 내부 구현으로, /api/admission/complete API에서 사용.
     * ZREM은 멱등성을 보장하므로 이미 제거된 멤버는 0 반환.
     *
     * @return true if removed, false if not found
     */
    public boolean removeFromActive(String movieId, String requestId) {
        String activeKey = activeSessionsKey("movie", movieId);
        try {
            ensureKeyType(activeKey, "ZSET");
            Long removed = zSetOps.remove(activeKey, requestId);
            return removed != null && removed > 0;
        } catch (RedisSystemException e) {
            if (isWrongTypeError(e)) {
                redisTemplate.delete(activeKey);
            }
            return false;
        }
    }
}
