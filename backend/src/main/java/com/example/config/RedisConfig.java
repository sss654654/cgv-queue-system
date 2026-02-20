package com.example.config;

import com.example.admission.ws.WebSocketBroadcastListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration: data access + Pub/Sub subscription.
 *
 * <p>Data access:</p>
 * StringRedisSerializer on all key/value positions so that Redis keys
 * are human-readable and Lua scripts can operate on plain strings.
 *
 * <p>Pub/Sub:</p>
 * A single {@link RedisMessageListenerContainer} subscribes to the
 * "queue:notifications" channel. Every pod runs this container, so all
 * pods receive every published message. The {@link WebSocketBroadcastListener}
 * then routes the message to local STOMP subscribers.
 *
 * @see WebSocketBroadcastListener
 * @see com.example.admission.ws.WebSocketBroadcastService
 */
@Configuration
public class RedisConfig {

    /**
     * Primary RedisTemplate with String serializers for all positions.
     * Used by AdmissionService, LoadBalancingOptimizer, WebSocketBroadcastService,
     * and other Redis-backed components.
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        StringRedisSerializer serializer = new StringRedisSerializer();
        template.setKeySerializer(serializer);
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(serializer);
        template.setHashValueSerializer(serializer);

        template.afterPropertiesSet();
        return template;
    }

    // =========================================================================
    // Redis Pub/Sub configuration
    // =========================================================================

    /**
     * Container that manages Redis Pub/Sub subscriptions.
     * Subscribes to "queue:notifications" and delegates to WebSocketBroadcastListener.
     *
     * <p>WebSocketBroadcastListener implements MessageListener directly,
     * so it is registered without a MessageListenerAdapter wrapper.
     * The container manages the subscription thread and reconnection
     * on Redis failover.</p>
     *
     * <p>Single-channel architecture: all message types (ADMISSION, TIMEOUT,
     * STATS, SOLD_OUT) flow through one channel. The listener routes by
     * the JSON "type" field. This avoids managing multiple subscriptions
     * and keeps the Pub/Sub overhead minimal.</p>
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            WebSocketBroadcastListener broadcastListener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // Single channel subscription: queue:notifications (event-type JSON routing)
        container.addMessageListener(broadcastListener, new ChannelTopic("queue:notifications"));

        return container;
    }
}
