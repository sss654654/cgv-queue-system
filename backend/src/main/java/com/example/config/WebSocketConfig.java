package com.example.config;

import com.example.admission.ws.WebSocketSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.messaging.SessionConnectedEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;
import org.springframework.web.socket.messaging.SessionSubscribeEvent;
import org.springframework.web.socket.messaging.SessionUnsubscribeEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket + STOMP message broker configuration.
 *
 * Key design decisions:
 * <ul>
 *   <li>Heartbeat: 30s (balances keepalive vs CPU, matches ALB idle timeout 60s)</li>
 *   <li>Send buffer: 2MB (handles burst stats broadcast to many clients)</li>
 *   <li>Send timeout: 30s (slow clients are dropped rather than blocking threads)</li>
 *   <li>Message size: 128KB (sufficient for JSON payloads, prevents abuse)</li>
 *   <li>WebSocketSessionManager decorator: tracks sessions for graceful shutdown</li>
 * </ul>
 *
 * STOMP topics:
 * <pre>
 *   /topic/admission/{requestId}    - individual admission notification
 *   /topic/timeout/{requestId}      - individual timeout notification
 *   /topic/stats/movie/{movieId}    - queue stats broadcast (every 1s)
 * </pre>
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final WebSocketSessionManager webSocketSessionManager;

    // Connection statistics
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final ConcurrentHashMap<String, Long> sessionConnectTimes = new ConcurrentHashMap<>();

    public WebSocketConfig(WebSocketSessionManager webSocketSessionManager) {
        this.webSocketSessionManager = webSocketSessionManager;
    }

    /**
     * Message broker configuration.
     * - Simple in-memory broker for /topic prefix
     * - 30s heartbeat (server->client and client->server)
     * - /app prefix for application-bound messages
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic")
                .setHeartbeatValue(new long[]{30000, 30000})
                .setTaskScheduler(heartBeatScheduler());

        config.setApplicationDestinationPrefixes("/app");
        config.setPreservePublishOrder(true);

        log.info("WebSocket message broker configured (heartbeat: 30s)");
    }

    /**
     * STOMP endpoint registration.
     * - /ws endpoint with SockJS fallback
     * - Allow all origins (ALB handles CORS in prod)
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("*")
                .withSockJS()
                .setStreamBytesLimit(512 * 1024)
                .setHttpMessageCacheSize(1000)
                .setDisconnectDelay(30 * 1000)
                .setHeartbeatTime(30 * 1000);

        log.info("STOMP endpoint registered: /ws (SockJS enabled)");
    }

    /**
     * WebSocket transport layer configuration.
     * - 2MB send buffer to handle burst broadcast scenarios
     * - 30s send timeout (slow clients are dropped)
     * - 128KB message size limit
     * - WebSocketSessionManager decorator for graceful shutdown tracking
     */
    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registry) {
        registry
                .setSendBufferSizeLimit(2048 * 1024)   // 2MB
                .setSendTimeLimit(30000)                 // 30s
                .setMessageSizeLimit(128 * 1024)         // 128KB
                .setTimeToFirstMessage(60000)            // 60s
                .addDecoratorFactory(webSocketSessionManager.decoratorFactory());

        log.info("WebSocket transport configured (buffer: 2MB, timeout: 30s, msgLimit: 128KB)");
    }

    /**
     * Inbound channel thread pool for handling incoming STOMP frames.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(20)
                .maxPoolSize(100)
                .keepAliveSeconds(60)
                .queueCapacity(500);

        registration.interceptors(new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(
                        message, StompHeaderAccessor.class);
                if (accessor != null && accessor.getCommand() != null && log.isDebugEnabled()) {
                    log.debug("Inbound STOMP: {} from session {}",
                            accessor.getCommand(), accessor.getSessionId());
                }
                return message;
            }
        });
    }

    /**
     * Outbound channel thread pool for sending STOMP frames to clients.
     */
    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor()
                .corePoolSize(20)
                .maxPoolSize(100)
                .keepAliveSeconds(60)
                .queueCapacity(500);
    }

    /**
     * Dedicated scheduler for heartbeat and broker tasks.
     */
    @Bean
    public TaskScheduler heartBeatScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("ws-heartbeat-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        scheduler.initialize();
        return scheduler;
    }

    // =========================================================================
    // WebSocket event listeners for monitoring
    // =========================================================================

    @EventListener
    public void handleWebSocketConnectListener(SessionConnectedEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        long current = activeConnections.incrementAndGet();
        totalConnections.incrementAndGet();
        sessionConnectTimes.put(sessionId, System.currentTimeMillis());

        log.info("WebSocket connected: {} (active: {})", sessionId, current);

        if (current > 1000) {
            log.warn("High connection count: {} (monitor load)", current);
        }
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String sessionId = accessor.getSessionId();

        long current = activeConnections.decrementAndGet();
        Long connectTime = sessionConnectTimes.remove(sessionId);

        String durationInfo = "";
        if (connectTime != null) {
            long durationSec = (System.currentTimeMillis() - connectTime) / 1000;
            durationInfo = " (duration: " + durationSec + "s)";
        }

        log.info("WebSocket disconnected: {}{} (active: {})", sessionId, durationInfo, current);
    }

    @EventListener
    public void handleSubscribeEvent(SessionSubscribeEvent event) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
        String destination = accessor.getDestination();

        if (log.isDebugEnabled()) {
            log.debug("STOMP subscribe: {} -> {}", accessor.getSessionId(), destination);
        }

        if (destination != null && destination.startsWith("/topic/admission/")) {
            String requestId = destination.substring("/topic/admission/".length());
            log.info("Admission topic subscribed: session={}, requestId={}", accessor.getSessionId(), requestId);
        }
    }

    @EventListener
    public void handleUnsubscribeEvent(SessionUnsubscribeEvent event) {
        if (log.isDebugEnabled()) {
            StompHeaderAccessor accessor = StompHeaderAccessor.wrap(event.getMessage());
            log.debug("STOMP unsubscribe: {} (sub: {})",
                    accessor.getSessionId(), accessor.getSubscriptionId());
        }
    }

    // =========================================================================
    // Monitoring accessors
    // =========================================================================

    /**
     * Current WebSocket connection statistics (used by monitoring endpoints).
     */
    public Map<String, Object> getConnectionStats() {
        return Map.of(
                "activeConnections", activeConnections.get(),
                "totalConnections", totalConnections.get(),
                "averageSessionDurationMs", calculateAverageSessionDuration(),
                "timestamp", System.currentTimeMillis()
        );
    }

    private long calculateAverageSessionDuration() {
        if (sessionConnectTimes.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        return sessionConnectTimes.values().stream()
                .mapToLong(connectTime -> now - connectTime)
                .sum() / sessionConnectTimes.size();
    }

    /**
     * Reset cumulative connection counter (admin use).
     */
    public void resetConnectionStats() {
        totalConnections.set(activeConnections.get());
        log.info("WebSocket connection stats reset");
    }
}
