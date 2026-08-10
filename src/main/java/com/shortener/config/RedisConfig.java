package com.shortener.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis configuration for URL Shortener.
 * 
 * Configures Redis connection and RedisTemplate for string-based operations.
 */
@Configuration
public class RedisConfig {

    /**
     * Creates RedisTemplate for String key-value operations.
     * 
     * Used for:
     * - URL mappings (url:{shortCode} -> longUrl)
     * - Click counters (clicks:{shortCode} -> count)
     * - Rate limiting (rate:ip:{ip} -> sorted set)
     * 
     * @param connectionFactory Redis connection factory
     * @return RedisTemplate configured for String operations
     */
    @Bean
    public RedisTemplate<String, String> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }
}




