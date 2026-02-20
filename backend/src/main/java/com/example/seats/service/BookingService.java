package com.example.seats.service;

import com.example.admission.dto.BookingResult;
import com.example.admission.ws.WebSocketBroadcastService;
import com.example.seats.entity.Booking;
import com.example.seats.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * BookingService - 예매 완료 처리 (원자적)
 *
 * 설계:
 * - booking_complete.lua로 원자적 처리:
 *   1) ZREM sessions:{movieId}:active (활성 세션에서 제거)
 *   2) SADD booked:{movieId}:{theaterId} (예매 좌석 기록)
 *   3) INCR booking:completed:{movieId} (완료 카운터)
 *   4) 6000석 도달 시 sold-out:{movieId} 플래그 SET EX 3600
 *
 * - RDS 비동기 저장 (@Async)
 * - SOLD_OUT 발생 시 WebSocket 브로드캐스트
 *
 * Redis keys (Hash Tag: {movieId} ensures same Redis slot):
 * - sessions:{movieId}:active            (Sorted Set)
 * - booked:{movieId}:{theaterId}         (Set)
 * - booking:completed:{movieId}          (String counter)
 * - sold-out:{movieId}                   (String flag, TTL 3600s)
 *
 * Total seats: 6,000 (20 theaters x 300 seats)
 */
@Service
public class BookingService {

    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);
    private static final int TOTAL_SEATS = 6000;
    private static final int PRICE_PER_SEAT = 15000;

    private final RedisTemplate<String, String> redisTemplate;
    private final BookingRepository bookingRepository;
    private final WebSocketBroadcastService broadcastService;
    private RedisScript<List> bookingCompleteScript;

    public BookingService(RedisTemplate<String, String> redisTemplate,
                          BookingRepository bookingRepository,
                          WebSocketBroadcastService broadcastService) {
        this.redisTemplate = redisTemplate;
        this.bookingRepository = bookingRepository;
        this.broadcastService = broadcastService;
    }

    @PostConstruct
    private void loadScripts() {
        this.bookingCompleteScript = RedisScript.of(
                new ClassPathResource("scripts/booking_complete.lua"), List.class);
        logger.info("booking_complete.lua 스크립트 로드 완료");
    }

    /**
     * 예매 완료 처리.
     * Redis Lua 스크립트로 원자적 처리 후 RDS 비동기 저장.
     *
     * @param movieId   영화 ID
     * @param theaterId 상영관 ID
     * @param seatIds   예매 좌석 목록
     * @param requestId 요청자 ID (활성 세션 멤버)
     * @return BookingResult - COMPLETED 또는 ALREADY_COMPLETED
     */
    public BookingResult completeBooking(String movieId, String theaterId,
                                         List<String> seatIds, String requestId) {
        // Redis keys (Hash Tag 사용 - 같은 슬롯 배치)
        String activeKey = "sessions:{" + movieId + "}:active";
        String bookedKey = "booked:{" + movieId + "}:" + theaterId;
        String completedKey = "booking:completed:{" + movieId + "}";
        String soldOutKey = "sold-out:{" + movieId + "}";

        // seatIds를 comma-separated 문자열로 변환
        String seatsCsv = String.join(",", seatIds);

        try {
            @SuppressWarnings("unchecked")
            List<Object> result = redisTemplate.execute(
                    bookingCompleteScript,
                    Arrays.asList(activeKey, bookedKey, completedKey, soldOutKey),
                    requestId,
                    seatsCsv,
                    String.valueOf(TOTAL_SEATS)
            );

            if (result == null || result.isEmpty()) {
                logger.error("booking_complete.lua 실행 결과 null - requestId={}", requestId);
                return new BookingResult("ERROR", 0, TOTAL_SEATS, false);
            }

            long status = toLong(result.get(0));
            String statusText = result.get(1).toString();

            if (status == 0) {
                // 이미 완료된 예매 (멱등성)
                logger.info("이미 완료된 예매 - requestId={}, movieId={}", requestId, movieId);
                return new BookingResult("ALREADY_COMPLETED", 0, 0, false);
            }

            // 성공 - 카운터와 sold-out 여부 확인
            long completedCount = toLong(result.get(2));
            long isSoldOut = toLong(result.get(3));
            long remainingSeats = Math.max(0, TOTAL_SEATS - completedCount);
            boolean soldOut = isSoldOut == 1;

            logger.info("예매 완료 - movieId={}, theaterId={}, seats={}, requestId={}, " +
                            "completedCount={}/{}, soldOut={}",
                    movieId, theaterId, seatIds, requestId,
                    completedCount, TOTAL_SEATS, soldOut);

            // RDS 비동기 저장
            int totalPrice = seatIds.size() * PRICE_PER_SEAT;
            saveBookingAsync(movieId, theaterId, seatIds, totalPrice, requestId);

            // 매진 감지 시 WebSocket 브로드캐스트
            if (soldOut) {
                broadcastSoldOut(movieId);
            }

            return new BookingResult("COMPLETED", completedCount, remainingSeats, soldOut);

        } catch (Exception e) {
            logger.error("예매 완료 처리 Redis 오류 - movieId={}, theaterId={}, requestId={}",
                    movieId, theaterId, requestId, e);
            return new BookingResult("ERROR", 0, TOTAL_SEATS, false);
        }
    }

    /**
     * RDS 비동기 저장 - 예매 정보를 MySQL에 영속화한다.
     * Redis 원자적 처리 후 비동기로 실행되므로 예매 응답 지연에 영향 없음.
     */
    @Async("bookingExecutor")
    public void saveBookingAsync(String movieId, String theaterId,
                                  List<String> seatIds, int totalPrice, String requestId) {
        try {
            String bookingId = UUID.randomUUID().toString();
            Booking booking = new Booking(
                    bookingId, movieId, theaterId,
                    seatIds, totalPrice, requestId
            );

            bookingRepository.save(booking);
            logger.info("예매 RDS 저장 완료 - bookingId={}, movieId={}, theaterId={}, seats={}",
                    bookingId, movieId, theaterId, seatIds);

        } catch (Exception e) {
            // 비동기 저장 실패는 로그만 남기고 Redis 상태는 유지
            // 별도 보상 로직(재시도 큐 등)으로 처리 가능
            logger.error("예매 RDS 저장 실패 - movieId={}, theaterId={}, requestId={}, seats={}",
                    movieId, theaterId, requestId, seatIds, e);
        }
    }

    /**
     * 매진 감지 시 해당 영화의 모든 대기 사용자에게 SOLD_OUT 브로드캐스트.
     * Redis Pub/Sub -> 모든 Pod의 WebSocketBroadcastListener -> /topic/stats/movie/{movieId}
     */
    private void broadcastSoldOut(String movieId) {
        try {
            logger.warn("SOLD OUT 감지 - movieId={}, 전체 {}석 매진", movieId, TOTAL_SEATS);
            broadcastService.broadcastSoldOut(movieId);
            logger.info("SOLD OUT 브로드캐스트 완료 - movieId={}", movieId);
        } catch (Exception e) {
            logger.error("SOLD OUT 브로드캐스트 실패 - movieId={}", movieId, e);
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
