package com.lifedp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 通用三级缓存客户端，提供防击穿/防雪崩/防穿透能力。
 *
 * 架构: L1(本地ConcurrentHashMap) → L2(Redis逻辑过期) → L3(DB)
 *
 * 核心策略:
 *  - 逻辑过期: Redis key无物理TTL，通过RedisData.expireTime字段判断过期，
 *    过期时获取SETNX互斥锁后异步重建，当前请求直接返回旧数据不阻塞。
 *  - 互斥锁: SETNX + 10秒自动过期防死锁，未获取锁时sleep 50ms重试(最多10次)。
 *  - 降级: 所有Redis操作try-catch，异常时自动降级到DB或本地缓存。
 */
@Slf4j
@Component
public class CacheClient {

    private static final int MAX_LOCAL_CACHE_SIZE = 1000;
    private static final long LOCAL_CACHE_TTL_SECONDS = 60;
    // 异步重建缓存的线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    private static final long LOCK_TTL_SECONDS = 10;
    private static final long RETRY_SLEEP_MS = 50;
    private static final int MAX_RETRY = 10;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private final ConcurrentHashMap<String, CacheEntry> localCache = new ConcurrentHashMap<>();

    private static class CacheEntry {
        final Object data;
        final long expireAt;

        CacheEntry(Object data, long expireAt) {
            this.data = data;
            this.expireAt = expireAt;
        }
    }

    /**
     * 逻辑过期查询 (适用于热点数据、高并发读场景)。
     * 决策树:
     *   L1命中 → 直接返回
     *   L2命中且未逻辑过期 → 写回L1 → 返回
     *   L2命中但已逻辑过期 → 尝试获取互斥锁 → 成功则异步重建 / 失败则返回旧数据
     *   L2未命中 → 获取互斥锁 → DB加载 → 写入L2 → 返回
     */
    // L1(本地) → L2(Redis逻辑过期) → L3(DB)，过期时异步重建不阻塞请求
    public <R, ID> R queryWithLogicalExpire(
            String keyPrefix, String lockKeyPrefix, ID id,
            Class<R> type, Function<ID, R> dbFallback,
            Long ttl, TimeUnit unit) {

        String cacheKey = keyPrefix + id;
        String lockKey = lockKeyPrefix + id;

        R localResult = getFromLocalCache(cacheKey, type);
        if (localResult != null) {
            return localResult;
        }

        String json;
        try {
            json = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("Redis GET异常, cacheKey={}", cacheKey, e);
            return fallbackToDb(id, dbFallback);
        }

        if (StrUtil.isNotBlank(json)) {
            RedisData redisData;
            try {
                redisData = JSONUtil.toBean(json, RedisData.class);
            } catch (Exception e) {
                log.warn("Redis数据解析异常, cacheKey={}", cacheKey, e);
                return fallbackToDb(id, dbFallback);
            }

            if (redisData.getData() == null) {
                return fallbackToDbWithCache(keyPrefix, lockKey, id, type, dbFallback, ttl, unit);
            }

            R result = extractData(redisData.getData(), type);
            LocalDateTime expireTime = redisData.getExpireTime();

            if (expireTime != null && expireTime.isAfter(LocalDateTime.now())) {
                setToLocalCache(cacheKey, result, LOCAL_CACHE_TTL_SECONDS);
                return result;
            }

            if (tryLock(lockKey)) {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        R fresh = dbFallback.apply(id);
                        if (fresh != null) {
                            setWithLogicalExpire(cacheKey, fresh, ttl, unit);
                            setToLocalCache(cacheKey, fresh, LOCAL_CACHE_TTL_SECONDS);
                        }
                    } catch (Exception e) {
                        log.warn("异步重建缓存异常, cacheKey={}", cacheKey, e);
                    } finally {
                        unlock(lockKey);
                    }
                });
            }
            return result;
        }

        return fallbackToDbWithCache(keyPrefix, lockKey, id, type, dbFallback, ttl, unit);
    }

    /**
     * 互斥锁查询 (适用于缓存击穿保护，如冷数据突然变热)。
     * SETNX占锁 → DB加载 → 写回Redis → 释放锁，未获取锁时最多重试10次。
     */
    // 互斥锁防缓存击穿: SETNX + 最多10次重试
    public <R, ID> R queryWithMutex(
            String keyPrefix, String lockKeyPrefix, ID id,
            Class<R> type, Function<ID, R> dbFallback,
            Long ttl, TimeUnit unit) {

        String cacheKey = keyPrefix + id;
        String lockKey = lockKeyPrefix + id;

        String json;
        try {
            json = stringRedisTemplate.opsForValue().get(cacheKey);
        } catch (Exception e) {
            log.warn("Redis GET异常, cacheKey={}", cacheKey, e);
            return dbFallback.apply(id);
        }

        if (StrUtil.isNotBlank(json)) {
            try {
                return JSONUtil.toBean(json, type);
            } catch (Exception e) {
                log.warn("Redis数据解析异常, cacheKey={}", cacheKey, e);
            }
        }

        for (int i = 0; i < MAX_RETRY; i++) {
            if (tryLock(lockKey)) {
                try {
                    R result = dbFallback.apply(id);
                    if (result != null) {
                        stringRedisTemplate.opsForValue().set(cacheKey,
                                JSONUtil.toJsonStr(result), ttl, unit);
                    }
                    return result;
                } catch (Exception e) {
                    log.warn("DB查询或缓存写入异常, cacheKey={}", cacheKey, e);
                    return null;
                } finally {
                    unlock(lockKey);
                }
            }
            try {
                Thread.sleep(RETRY_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return dbFallback.apply(id);
            }
        }

        return dbFallback.apply(id);
    }

    /**
     * 设置逻辑过期缓存: Redis key无物理TTL，通过RedisData.expireTime字段判断过期。
     * 同时写入本地缓存作为L1加速。
     */
    public void setWithLogicalExpire(String key, Object value, Long ttl, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(ttl)));
        redisData.setData(value);
        try {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        } catch (Exception e) {
            log.warn("Redis SET逻辑过期异常, key={}", key, e);
        }
        setToLocalCache(key, value, LOCAL_CACHE_TTL_SECONDS);
    }

    /**
     * 分布式互斥锁: SETNX + 10秒自动过期防死锁。
     */
    public boolean tryLock(String key) {
        try {
            Boolean result = stringRedisTemplate.opsForValue()
                    .setIfAbsent(key, "1", LOCK_TTL_SECONDS, TimeUnit.SECONDS);
            return Boolean.TRUE.equals(result);
        } catch (Exception e) {
            log.warn("Redis SETNX异常, key={}", key, e);
            return false;
        }
    }

    public void unlock(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis DEL异常, key={}", key, e);
        }
    }

    /**
     * 失效缓存: 同时清理本地L1和Redis L2。
     */
    public void invalidateAll(String keyPattern) {
        invalidateLocalCache(keyPattern);
        try {
            Set<String> keys = scanKeys(keyPattern);
            if (keys != null && !keys.isEmpty()) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("Redis DEL pattern异常, pattern={}", keyPattern, e);
        }
    }

    /** 使用 SCAN 命令安全遍历 keys（生产环境安全，避免 KEYS 阻塞） */
    private Set<String> scanKeys(String keyPattern) {
        Set<String> keys = new java.util.HashSet<>();
        stringRedisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            try (org.springframework.data.redis.core.Cursor<byte[]> cursor = connection.scan(
                    org.springframework.data.redis.core.ScanOptions.scanOptions()
                            .match(keyPattern).count(100).build())) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next()));
                }
            } catch (Exception e) {
                log.warn("Redis SCAN failed, pattern={}", keyPattern, e);
            }
            return null;
        });
        return keys;
    }

    @SuppressWarnings("unchecked")
    private <R> R getFromLocalCache(String key, Class<R> type) {
        CacheEntry entry = localCache.get(key);
        if (entry == null) {
            return null;
        }
        if (System.currentTimeMillis() > entry.expireAt) {
            localCache.remove(key);
            return null;
        }
        return (R) entry.data;
    }

    private void setToLocalCache(String key, Object value, long ttlSeconds) {
        if (localCache.size() >= MAX_LOCAL_CACHE_SIZE) {
            evictLocalCache();
        }
        long expireAt = System.currentTimeMillis() + ttlSeconds * 1000;
        localCache.put(key, new CacheEntry(value, expireAt));
    }

    // 本地缓存清理: 先删过期条目，若仍超限则全量清空
    private void evictLocalCache() {
        long now = System.currentTimeMillis();
        localCache.entrySet().removeIf(e -> e.getValue().expireAt < now);
        if (localCache.size() >= MAX_LOCAL_CACHE_SIZE) {
            localCache.clear();
        }
    }

    private void invalidateLocalCache(String keyPattern) {
        if (keyPattern.contains("*")) {
            localCache.keySet().removeIf(key -> key.startsWith(keyPattern.replace("*", "")));
        } else {
            localCache.keySet().removeIf(key -> key.startsWith(keyPattern));
        }
    }

    @SuppressWarnings("unchecked")
    private <R> R extractData(Object dataObj, Class<R> type) {
        if (type.isInstance(dataObj)) {
            return (R) dataObj;
        }
        return JSONUtil.toBean(JSONUtil.toJsonStr(dataObj), type);
    }

    private <R, ID> R fallbackToDb(ID id, Function<ID, R> dbFallback) {
        try {
            return dbFallback.apply(id);
        } catch (Exception e) {
            log.warn("DB降级查询异常", e);
            return null;
        }
    }

    private <R, ID> R fallbackToDbWithCache(
            String keyPrefix, String lockKey, ID id,
            Class<R> type, Function<ID, R> dbFallback,
            Long ttl, TimeUnit unit) {

        if (tryLock(lockKey)) {
            try {
                R result = dbFallback.apply(id);
                if (result != null) {
                    String cacheKey = keyPrefix + id;
                    setWithLogicalExpire(cacheKey, result, ttl, unit);
                }
                return result;
            } catch (Exception e) {
                log.warn("DB查询异常, lockKey={}", lockKey, e);
                return null;
            } finally {
                unlock(lockKey);
            }
        }

        for (int i = 0; i < MAX_RETRY; i++) {
            try {
                Thread.sleep(RETRY_SLEEP_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return dbFallback.apply(id);
            }

            String fullKey = keyPrefix + id;
            String json;
            try {
                json = stringRedisTemplate.opsForValue().get(fullKey);
            } catch (Exception ignored) {
                continue;
            }
            if (StrUtil.isNotBlank(json)) {
                try {
                    RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                    if (redisData.getData() != null) {
                        return extractData(redisData.getData(), type);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        return dbFallback.apply(id);
    }
}
