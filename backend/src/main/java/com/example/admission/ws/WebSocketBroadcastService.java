package com.example.admission.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Redis Pub/Sub publisher for WebSocket broadcast (multi-pod).
 *
 * All components that need to send real-time notifications to clients
 * should use this service instead of SimpMessagingTemplate directly.
 * Messages are published to the single "queue:notifications" Redis channel,
 * so every pod's {@link WebSocketBroadcastListener} receives them and routes
 * to local WebSocket subscribers. This ensures multi-pod consistency:
 * a client connected to Pod-A still receives a notification triggered on Pod-B.
 *
 * <p>Message format (JSON with mandatory "type" field):</p>
 * <pre>
 *   { "type": "ADMISSION|TIMEOUT|STATS|SOLD_OUT", ...fields, "timestamp": epoch }
 * </pre>
 *
 * <p>Routing (handled by WebSocketBroadcastListener):</p>
 * <ul>
 *   <li>ADMISSION  -> /topic/admission/{requestId}</li>
 *   <li>TIMEOUT    -> /topic/timeout/{requestId}</li>
 *   <li>STATS      -> /topic/stats/movie/{movieId}</li>
 *   <li>SOLD_OUT   -> /topic/stats/movie/{movieId}</li>
 * </ul>
 *
 * @see WebSocketBroadcastListener
 * @see WebSocketUpdateService (legacy single-pod fallback)
 */
@Service
public class WebSocketBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBroadcastService.class);
    private static final String CHANNEL = "queue:notifications";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public WebSocketBroadcastService(StringRedisTemplate redisTemplate,
                                     ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Publish admission notification.
     * Called by QueueProcessor after admitting a user from the waiting queue.
     *
     * @param requestId unique request identifier of the admitted user
     * @param movieId   movie that the user was waiting for
     */
    public void notifyAdmission(String requestId, String movieId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "ADMISSION");
        payload.put("status", "ADMITTED");
        payload.put("action", "REDIRECT_TO_SEATS");
        payload.put("requestId", requestId);
        payload.put("movieId", movieId);
        payload.put("timestamp", System.currentTimeMillis());
        publish(payload);

        log.info("Published ADMISSION: requestId={}..., movieId={}",
                truncateId(requestId), movieId);
    }

    /**
     * Publish timeout notification.
     * Called by SessionTimeoutProcessor when an active session expires.
     *
     * @param requestId unique request identifier of the timed-out user
     * @param movieId   movie that the session was associated with
     */
    public void notifyTimeout(String requestId, String movieId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "TIMEOUT");
        payload.put("status", "TIMEOUT");
        payload.put("action", "REDIRECT_TO_MOVIES");
        payload.put("requestId", requestId);
        payload.put("movieId", movieId);
        payload.put("timestamp", System.currentTimeMillis());
        publish(payload);

        log.warn("Published TIMEOUT: requestId={}..., movieId={}",
                truncateId(requestId), movieId);
    }

    /**
     * Broadcast queue statistics for a specific movie.
     * Called by RealtimeStatsBroadcaster every 1 second.
     *
     * <p>broadcast-only architecture: instead of sending individual rank
     * updates (O(N) per-user ZRANK), we publish aggregate stats once.
     * The client computes its approximate rank from its initial position
     * and the monotonically increasing processedCount.</p>
     *
     * @param movieId        target movie
     * @param waitingCount   current number of users in the waiting queue
     * @param activeCount    current number of users in active sessions
     * @param processedCount cumulative number of users processed (monotonically increasing)
     */
    public void broadcastQueueStats(String movieId, long waitingCount,
                                    long activeCount, long processedCount) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "STATS");
        payload.put("movieId", movieId);
        payload.put("waitingCount", waitingCount);
        payload.put("activeCount", activeCount);
        payload.put("processedCount", processedCount);
        payload.put("timestamp", System.currentTimeMillis());
        publish(payload);

        log.debug("Published STATS: movieId={}, waiting={}, active={}, processed={}",
                movieId, waitingCount, activeCount, processedCount);
    }

    /**
     * Broadcast sold-out event for a specific movie.
     * Signals all waiting clients that no more seats are available.
     *
     * @param movieId the movie that is sold out
     */
    public void broadcastSoldOut(String movieId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "SOLD_OUT");
        payload.put("movieId", movieId);
        payload.put("soldOut", true);
        payload.put("timestamp", System.currentTimeMillis());
        publish(payload);

        log.info("Published SOLD_OUT: movieId={}", movieId);
    }

    /**
     * Publish a JSON message to the Redis Pub/Sub channel.
     * Serialization or Redis errors are caught and logged; they do not
     * propagate to the caller so that the main business flow continues.
     */
    private void publish(Map<String, Object> payload) {
        try {
            String json = objectMapper.writeValueAsString(payload);
            redisTemplate.convertAndSend(CHANNEL, json);
        } catch (Exception e) {
            log.error("Failed to publish to {}: type={}, error={}",
                    CHANNEL, payload.get("type"), e.getMessage());
        }
    }

    private String truncateId(String id) {
        if (id == null) return "null";
        return id.length() > 8 ? id.substring(0, 8) : id;
    }
}
