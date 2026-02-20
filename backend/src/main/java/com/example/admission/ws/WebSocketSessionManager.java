package com.example.admission.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tracks all active WebSocket sessions for graceful shutdown support.
 *
 * During normal operation this component simply maintains a set of open
 * sessions. When {@link #prepareForShutdown()} is called (typically by
 * a GracefulShutdownManager's @PreDestroy hook), it sends a RECONNECT
 * text frame to every connected client and then closes the connections
 * with status 1000 (NORMAL_CLOSURE). Clients receiving this frame should
 * immediately reconnect to another pod via the load balancer.
 *
 * <p>Integration with WebSocketConfig:</p>
 * The {@link #decoratorFactory()} method returns a
 * {@link WebSocketHandlerDecoratorFactory} that is registered in
 * {@code configureWebSocketTransport()} to intercept connect/disconnect
 * events without requiring a separate HandshakeInterceptor.
 */
@Component
public class WebSocketSessionManager {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionManager.class);

    private final Set<WebSocketSession> activeSessions = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    /**
     * Register a newly opened WebSocket session.
     */
    public void registerSession(WebSocketSession session) {
        activeSessions.add(session);
        if (log.isDebugEnabled()) {
            log.debug("Session registered: {} (total: {})", session.getId(), activeSessions.size());
        }
    }

    /**
     * Unregister a closed WebSocket session.
     */
    public void unregisterSession(WebSocketSession session) {
        activeSessions.remove(session);
        if (log.isDebugEnabled()) {
            log.debug("Session unregistered: {} (total: {})", session.getId(), activeSessions.size());
        }
    }

    /**
     * @return the number of currently tracked sessions on this pod
     */
    public int getActiveSessionCount() {
        return activeSessions.size();
    }

    /**
     * @return true if shutdown has been initiated
     */
    public boolean isShuttingDown() {
        return shuttingDown.get();
    }

    /**
     * Graceful shutdown: send RECONNECT message and close all sessions.
     *
     * Called by GracefulShutdownManager during @PreDestroy.
     * The RECONNECT text frame tells the client to re-establish the
     * connection to another pod. Closure uses status 1000 (normal) so
     * the browser / SockJS client treats it as a clean disconnect.
     */
    public void prepareForShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) {
            log.info("Shutdown already in progress, skipping duplicate call");
            return;
        }

        int sessionCount = activeSessions.size();
        log.info("Preparing for shutdown: closing {} active WebSocket sessions", sessionCount);

        int closedCount = 0;
        int errorCount = 0;

        for (WebSocketSession session : activeSessions) {
            try {
                if (session.isOpen()) {
                    // Send RECONNECT hint so the client can reconnect to another pod
                    session.sendMessage(new TextMessage(
                            "{\"type\":\"RECONNECT\",\"reason\":\"SERVER_SHUTDOWN\"}"));
                    session.close(CloseStatus.NORMAL);
                    closedCount++;
                }
            } catch (IOException e) {
                errorCount++;
                log.warn("Failed to close session {}: {}", session.getId(), e.getMessage());
            }
        }

        activeSessions.clear();
        log.info("Shutdown complete: closed={}, errors={}, total={}", closedCount, errorCount, sessionCount);
    }

    /**
     * Returns a {@link WebSocketHandlerDecoratorFactory} that automatically
     * registers and unregisters sessions with this manager.
     *
     * <p>Usage in WebSocketConfig:</p>
     * <pre>
     *   registry.addDecoratorFactory(webSocketSessionManager.decoratorFactory());
     * </pre>
     */
    public WebSocketHandlerDecoratorFactory decoratorFactory() {
        return new WebSocketHandlerDecoratorFactory() {
            @Override
            public WebSocketHandler decorate(WebSocketHandler handler) {
                return new WebSocketHandlerDecorator(handler) {
                    @Override
                    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                        registerSession(session);
                        super.afterConnectionEstablished(session);
                    }

                    @Override
                    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message)
                            throws Exception {
                        super.handleMessage(session, message);
                    }

                    @Override
                    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
                            throws Exception {
                        unregisterSession(session);
                        super.afterConnectionClosed(session, closeStatus);
                    }
                };
            }
        };
    }
}
