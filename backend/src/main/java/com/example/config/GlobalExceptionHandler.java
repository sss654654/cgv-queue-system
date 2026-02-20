package com.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler - 전역 예외 처리
 *
 * 컨트롤러에서 try-catch를 제거하고 @RestControllerAdvice에서 통합 처리한다.
 * 각 예외 유형에 맞는 HTTP 상태 코드와 구조화된 에러 응답을 반환한다.
 *
 * 매핑:
 *   MethodArgumentNotValidException -> 400 Bad Request (필드별 에러 메시지)
 *   IllegalStateException           -> 409 Conflict (상태 충돌)
 *   IllegalArgumentException        -> 400 Bad Request (잘못된 인자)
 *   RedisConnectionFailureException -> 503 Service Unavailable (Redis 장애)
 *   Exception                       -> 500 Internal Server Error (기타)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * @Valid 유효성 검증 실패 -> 400 + 필드별 에러 메시지
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                errors.put(error.getField(), error.getDefaultMessage())
        );
        logger.warn("Validation failed: {}", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errors);
    }

    /**
     * 비즈니스 상태 충돌 (예: 이미 입장한 사용자 재입장) -> 409
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleIllegalState(IllegalStateException ex) {
        logger.warn("Illegal state: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "CONFLICT",
                "message", ex.getMessage()
        ));
    }

    /**
     * 잘못된 인자 (예: 빈 movieId) -> 400
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        logger.warn("Illegal argument: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "error", "BAD_REQUEST",
                "message", ex.getMessage()
        ));
    }

    /**
     * Redis 연결 장애 -> 503 Service Unavailable
     * Circuit Breaker 패턴과 함께 사용:
     *   클라이언트는 503을 받으면 재시도하고, Redis failover(~30초) 후 자동 복구된다.
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public ResponseEntity<Map<String, String>> handleRedisFailure(RedisConnectionFailureException ex) {
        logger.error("Redis connection failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "SERVICE_UNAVAILABLE",
                "message", "Queue service temporarily unavailable. Please retry."
        ));
    }

    /**
     * 기타 모든 예외 -> 500
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneral(Exception ex) {
        logger.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                "error", "INTERNAL_SERVER_ERROR",
                "message", "An unexpected error occurred."
        ));
    }
}
