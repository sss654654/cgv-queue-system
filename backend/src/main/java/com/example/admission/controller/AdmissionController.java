package com.example.admission.controller;

import com.example.admission.dto.*;
import com.example.admission.service.AdmissionService;
import com.example.admission.service.DynamicSessionCalculator;
import com.example.seats.entity.Booking;
import com.example.seats.repository.BookingRepository;
import com.example.seats.service.SeatService;
import com.example.seats.service.TheaterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Map;

/**
 * AdmissionController - 대기열 + 좌석/예매 통합 API
 *
 * 기존 엔드포인트:
 *   POST /api/admission/enter   - 대기열 진입
 *   POST /api/admission/leave   - 대기열 퇴장
 *   GET  /api/admission/status  - 사용자 상태 확인
 *   GET  /api/admission/system/config - 시스템 설정 조회
 *
 * 신규 엔드포인트 (2.2 spec):
 *   POST /api/admission/complete - 예매 완료 (좌석 lock -> booking 확정)
 *   GET  /api/theaters/{movieId} - 영화별 상영관 + 잔여 좌석
 *   POST /api/seats/select       - 원자적 멀티좌석 선점
 *   GET  /api/bookings           - 사용자 예매 내역 조회
 */
@RestController
@RequestMapping("/api")
@Validated
@Tag(name = "Admission API", description = "대기열 + 좌석/예매 관리 API")
public class AdmissionController {

    private static final Logger logger = LoggerFactory.getLogger(AdmissionController.class);

    private final AdmissionService admissionService;
    private final DynamicSessionCalculator sessionCalculator;
    private final SeatService seatService;
    private final TheaterService theaterService;
    private final BookingRepository bookingRepository;

    @Value("${admission.session-timeout-seconds:300}")
    private long sessionTimeoutSeconds;

    public AdmissionController(AdmissionService admissionService,
                               DynamicSessionCalculator sessionCalculator,
                               SeatService seatService,
                               TheaterService theaterService,
                               BookingRepository bookingRepository) {
        this.admissionService = admissionService;
        this.sessionCalculator = sessionCalculator;
        this.seatService = seatService;
        this.theaterService = theaterService;
        this.bookingRepository = bookingRepository;
    }

    // ========== 기존 대기열 엔드포인트 ==========

    @Operation(summary = "대기열 진입", description = "영화 예매 대기열에 진입합니다")
    @PostMapping("/admission/enter")
    public ResponseEntity<EnterResponse> enter(@Valid @RequestBody EnterRequest request) {
        EnterResponse response = admissionService.enter("movie", request.movieId(),
                request.requestId());

        // ADMITTED -> 200 OK (즉시 입장), WAITING -> 202 Accepted (대기열 등록)
        return (response.getStatus() == EnterResponse.Status.ADMITTED)
                ? ResponseEntity.ok(response)
                : ResponseEntity.accepted().body(response);
    }

    @Operation(summary = "대기열 퇴장", description = "대기열에서 퇴장합니다")
    @PostMapping("/admission/leave")
    public ResponseEntity<Void> leave(@Valid @RequestBody LeaveRequest request) {
        admissionService.leave("movie", request.getMovieId(), request.getRequestId());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "시스템 설정 조회", description = "대기열 시스템 설정을 조회합니다")
    @GetMapping("/admission/system/config")
    public ResponseEntity<Map<String, Object>> getSystemConfig() {
        var sessionInfo = sessionCalculator.getCalculationInfo();

        Map<String, Object> config = Map.of(
                "baseSessionsPerPod", sessionInfo.baseSessionsPerPod(),
                "waitTimePerPodSeconds", 10,
                "currentPodCount", sessionInfo.currentPodCount(),
                "maxTotalSessions", sessionInfo.calculatedMaxSessions(),
                "sessionTimeoutSeconds", sessionTimeoutSeconds,
                "dynamicScalingEnabled", sessionInfo.dynamicScalingEnabled(),
                "kubernetesAvailable", sessionInfo.kubernetesAvailable()
        );

        return ResponseEntity.ok(config);
    }

    @Operation(summary = "사용자 상태 확인", description = "사용자의 현재 대기열/활성 세션 상태를 확인합니다")
    @GetMapping("/admission/status")
    public ResponseEntity<Map<String, Object>> checkUserStatus(
            @RequestParam String movieId,
            @RequestParam String requestId) {

        // 활성 세션에 있는지 확인
        if (admissionService.isUserInActiveSession("movie", movieId, requestId)) {
            return ResponseEntity.ok(Map.of(
                    "status", "ACTIVE",
                    "action", "REDIRECT_TO_SEATS"
            ));
        }

        // 대기열에 있는지 확인
        Long rank = admissionService.getUserRank("movie", movieId, requestId);
        if (rank != null) {
            long totalWaiting = admissionService.getTotalWaitingCount("movie", movieId);
            return ResponseEntity.ok(Map.of(
                    "status", "WAITING",
                    "rank", rank,
                    "totalWaiting", totalWaiting
            ));
        }

        // 둘 다 없음
        return ResponseEntity.ok(Map.of(
                "status", "NOT_FOUND",
                "action", "REDIRECT_TO_MOVIES"
        ));
    }

    // ========== 신규 엔드포인트 (2.2 spec) ==========

    /**
     * 예매 완료 API - 좌석 선점 lock을 확정하고 Booking 레코드를 생성한다.
     * 활성 세션에서 사용자를 제거하고 매진 여부를 반환한다.
     */
    @Operation(summary = "예매 완료", description = "선점된 좌석의 예매를 확정합니다")
    @PostMapping("/admission/complete")
    public ResponseEntity<BookingResult> complete(@Valid @RequestBody CompleteRequest request) {
        logger.info("예매 완료 요청 - movieId={}, theaterId={}, seats={}, requestId={}",
                request.movieId(), request.theaterId(), request.seatIds(), request.requestId());

        // 1) 활성 세션에서 사용자 제거 (예매 완료 = 대기열 프로세스 종료)
        admissionService.completeAdmission("movie", request.movieId(), request.requestId());

        // 2) 예매된 좌석을 booked Set에 추가 (TheaterService 잔여 좌석 계산에 사용)
        String bookingId = "BK-" + System.currentTimeMillis() + "-" + request.requestId().substring(0, 8);
        int pricePerSeat = 15000;
        int totalPrice = pricePerSeat * request.seatIds().size();

        Booking booking = new Booking(
                bookingId, request.movieId(), request.theaterId(),
                request.seatIds(), totalPrice, request.requestId()
        );
        bookingRepository.save(booking);

        // 3) 전체 예매 수 / 잔여 좌석 계산
        long completedCount = bookingRepository.countByMovieId(request.movieId());
        long totalSeats = 6000L; // 20극장 x 300석
        long remainingSeats = Math.max(0, totalSeats - completedCount);
        boolean soldOut = remainingSeats <= 0;

        logger.info("예매 완료 - bookingId={}, completedCount={}, remainingSeats={}, soldOut={}",
                bookingId, completedCount, remainingSeats, soldOut);

        return ResponseEntity.ok(new BookingResult("COMPLETED", completedCount, remainingSeats, soldOut));
    }

    /**
     * 영화별 상영관 목록 + 잔여 좌석 조회
     */
    @Operation(summary = "상영관 조회", description = "영화별 상영관과 잔여 좌석 수를 조회합니다")
    @GetMapping("/theaters/{movieId}")
    public ResponseEntity<List<TheaterInfo>> getTheaters(@PathVariable String movieId) {
        List<TheaterInfo> theaters = theaterService.getTheaters(movieId);
        return ResponseEntity.ok(theaters);
    }

    /**
     * 원자적 멀티좌석 선점 (all-or-nothing)
     * seat_lock.lua 기반, 최대 4좌석, TTL 300초
     */
    @Operation(summary = "좌석 선점", description = "좌석을 원자적으로 선점합니다 (최대 4석)")
    @PostMapping("/seats/select")
    public ResponseEntity<SeatLockResult> selectSeats(@Valid @RequestBody SeatSelectionRequest request) {
        logger.info("좌석 선점 요청 - movieId={}, theaterId={}, seats={}, requestId={}",
                request.movieId(), request.theaterId(), request.seatIds(), request.requestId());

        SeatLockResult result = seatService.lockSeats(
                request.movieId(), request.theaterId(),
                request.seatIds(), request.requestId());

        if ("LOCKED".equals(result.status())) {
            return ResponseEntity.ok(result);
        } else {
            // 409 Conflict - 이미 선점된 좌석 존재
            return ResponseEntity.status(409).body(result);
        }
    }

    /**
     * 사용자 예매 내역 조회
     */
    @Operation(summary = "예매 내역 조회", description = "사용자의 예매 내역을 조회합니다")
    @GetMapping("/bookings")
    public ResponseEntity<List<Booking>> getBookings(@RequestParam String requestId) {
        List<Booking> bookings = bookingRepository.findByRequestId(requestId);
        return ResponseEntity.ok(bookings);
    }
}