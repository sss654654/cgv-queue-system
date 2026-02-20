package com.example.admission.ws;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Redis Pub/Sub subscriber for the "queue:notifications" channel.
 *
 * Every pod runs one instance of this listener. When a message arrives on
 * the channel, this class parses the JSON, determines the WebSocket topic
 * from the "type" field, and forwards the payload to all local STOMP
 * subscribers via {@link SimpMessagingTemplate}. Because every pod subscribes
 * to the same channel, clients receive the notification regardless of which
 * pod they are connected to.
 *
 * <p>Routing rules:</p>
 * <ul>
 *   <li>type=ADMISSION  -> /topic/admission/{requestId}</li>
 *   <li>type=TIMEOUT    -> /topic/timeout/{requestId}</li>
 *   <li>type=STATS      -> /topic/stats/movie/{movieId}</li>
 *   <li>type=SOLD_OUT   -> /topic/stats/movie/{movieId} (with soldOut=true)</li>
 * </ul>
 *
 * @see WebSocketBroadcastService
 */
@Component
public class WebSocketBroadcastListener implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketBroadcastListener.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    public WebSocketBroadcastListener(SimpMessagingTemplate messagingTemplate,
                                      ObjectMapper objectMapper) {
        this.messagingTemplate = messagingTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String body = null;
        try {
            body = new String(message.getBody(), StandardCharsets.UTF_8);
            Map<String, Object> payload = objectMapper.readValue(body, MAP_TYPE);

            String type = (String) payload.get("type");
            if (type == null) {
                log.warn("Received message without 'type' field, ignoring: {}", truncate(body));
                return;
            }

            String destination = resolveDestination(type, payload);
            if (destination == null) {
                // resolveDestination already logged the reason
                return;
            }

            // Send the parsed Map (not raw String) so Spring serializes it properly
            messagingTemplate.convertAndSend(destination, payload);

            log.debug("Routed Pub/Sub -> WebSocket: type={} -> {}", type, destination);

        } catch (Exception e) {
            log.error("Failed to process Pub/Sub message: body={}, error={}",
                    truncate(body), e.getMessage());
        }
    }

    /**
     * Map the message type to the correct STOMP destination.
     *
     * @return the topic path, or null if the type is unrecognized or required fields are missing
     */
    private String resolveDestination(String type, Map<String, Object> payload) {
        return switch (type) {
            case "ADMISSION" -> {
                Object requestId = payload.get("requestId");
                if (requestId == null) {
                    log.warn("ADMISSION message missing 'requestId'");
                    yield null;
                }
                yield "/topic/admission/" + requestId;
            }
            case "TIMEOUT" -> {
                Object requestId = payload.get("requestId");
                if (requestId == null) {
                    log.warn("TIMEOUT message missing 'requestId'");
                    yield null;
                }
                yield "/topic/timeout/" + requestId;
            }
            case "STATS" -> {
                Object movieId = payload.get("movieId");
                if (movieId == null) {
                    log.warn("STATS message missing 'movieId'");
                    yield null;
                }
                yield "/topic/stats/movie/" + movieId;
            }
            case "SOLD_OUT" -> {
                Object movieId = payload.get("movieId");
                if (movieId == null) {
                    log.warn("SOLD_OUT message missing 'movieId'");
                    yield null;
                }
                yield "/topic/stats/movie/" + movieId;
            }
            default -> {
                log.warn("Unknown message type '{}', ignoring", type);
                yield null;
            }
        };
    }

    /**
     * Truncate long message bodies for safe logging.
     */
    private String truncate(String text) {
        if (text == null) return "null";
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
