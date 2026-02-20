package com.example.seats.service;

import com.example.admission.dto.TheaterInfo;
import com.example.seats.entity.Theater;
import com.example.seats.repository.TheaterRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * TheaterService - 영화별 상영관 목록 + 잔여 좌석 조회
 *
 * 설계:
 * - DB에서 상영관 목록(20개 x 300석) 조회
 * - Redis SCARD로 booked:{movieId}:{theaterId} Set의 크기 = 예매 완료 좌석 수 (Hash Tag on movieId)
 * - availableSeats = totalSeats - bookedCount
 */
@Service
public class TheaterService {

    private static final Logger logger = LoggerFactory.getLogger(TheaterService.class);

    private final TheaterRepository theaterRepository;
    private final RedisTemplate<String, String> redisTemplate;

    public TheaterService(TheaterRepository theaterRepository,
                          RedisTemplate<String, String> redisTemplate) {
        this.theaterRepository = theaterRepository;
        this.redisTemplate = redisTemplate;
    }

    /**
     * 특정 영화의 모든 상영관과 잔여 좌석 수를 반환한다.
     * 20 theaters x 300 seats = 6,000 total
     *
     * @param movieId 영화 ID
     * @return 상영관별 잔여 좌석 정보 목록
     */
    public List<TheaterInfo> getTheaters(String movieId) {
        List<Theater> theaters = theaterRepository.findAll();
        List<TheaterInfo> result = new ArrayList<>(theaters.size());

        for (Theater theater : theaters) {
            int bookedCount = getBookedCount(movieId, theater.getTheaterId());
            int availableSeats = Math.max(0, theater.getTotalSeats() - bookedCount);

            result.add(new TheaterInfo(
                    theater.getTheaterId(),
                    theater.getName(),
                    theater.getTotalSeats(),
                    availableSeats
            ));
        }

        logger.debug("상영관 목록 조회 완료 - movieId: {}, theaters: {}개", movieId, result.size());
        return result;
    }

    /**
     * Redis SCARD로 특정 상영관의 예매 완료 좌석 수를 조회한다.
     * Key: booked:{movieId}:{theaterId} (Set, Hash Tag on movieId)
     */
    private int getBookedCount(String movieId, String theaterId) {
        try {
            String key = "booked:{" + movieId + "}:" + theaterId;
            Long count = redisTemplate.opsForSet().size(key);
            return count != null ? count.intValue() : 0;
        } catch (Exception e) {
            logger.error("Redis 예매 좌석 수 조회 실패 - movieId: {}, theaterId: {}",
                    movieId, theaterId, e);
            return 0;
        }
    }
}
