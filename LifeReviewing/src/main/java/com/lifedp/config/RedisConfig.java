package com.lifedp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Key 序列化：String
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Value 序列化：Jackson（自动处理 Long / String / 对象）
        GenericJackson2JsonRedisSerializer jsonSerializer = new GenericJackson2JsonRedisSerializer();
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Stream 消费者专用 StringRedisTemplate，使用独立连接避免
     * XREADGROUP BLOCK 阻塞主连接池影响正常请求。
     */
    @Bean("streamStringRedisTemplate")
    public StringRedisTemplate streamStringRedisTemplate(LettuceConnectionFactory factory) {
        LettuceConnectionFactory streamFactory = new LettuceConnectionFactory();
        streamFactory.setHostName(factory.getHostName());
        streamFactory.setPort(factory.getPort());
        streamFactory.setDatabase(factory.getDatabase());
        streamFactory.setPassword(factory.getPassword());
        // ★ 关键：不共享原生连接，否则 XREADGROUP BLOCK 会阻塞所有请求
        streamFactory.setShareNativeConnection(false);
        streamFactory.afterPropertiesSet();
        return new StringRedisTemplate(streamFactory);
    }
}