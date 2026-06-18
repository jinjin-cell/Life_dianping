package com.lifereviewing.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisConnection;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Redis操作工具类，封装String/Hash/Set/对象/计数器/Lua/Stream操作。
 *
 * 预编译3个Lua脚本:
 *   INCR_WITH_EXPIRE: 原子INCR+EXPIRE (首次设置TTL)
 *   CHECK_DEL:       原子校验验证码并删除 (GET→比对→DEL)
 *   SECKILL:         秒杀扣库存 (SISMEMBER防重复→GET检查→DECR→SADD)
 *
 * Stream操作通过RedisConnection低阶API实现 (XADD/XREADGROUP/XACK/XPENDING/XCLAIM/XDEL)
 * 因为Spring Data Redis 2.3的StreamOperations对Lettuce支持有限。
 */
@Slf4j
@Component
public class RedisHelper {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisTemplate<String, Object> redisTemplate;

    /** Stream 消费者专用连接，避免 XREADGROUP BLOCK 阻塞主连接池 */
    @Resource
    @Qualifier("streamStringRedisTemplate")
    private StringRedisTemplate streamStringRedisTemplate;

    private static final DefaultRedisScript<Long> INCR_WITH_EXPIRE_SCRIPT;

    private static final DefaultRedisScript<Long> CHECK_DEL_SCRIPT;

    private static final DefaultRedisScript<Long> SECKILL_LUA_SCRIPT;

    private static final DefaultRedisScript<Long> SECKILL_ROLLBACK_LUA_SCRIPT;

    // Lua脚本预编译
    static {
        // INCR + EXPIRE 原子操作
        INCR_WITH_EXPIRE_SCRIPT = new DefaultRedisScript<>();
        INCR_WITH_EXPIRE_SCRIPT.setResultType(Long.class);
        INCR_WITH_EXPIRE_SCRIPT.setScriptText(
            "local count = redis.call('INCRBY', KEYS[1], ARGV[1]) " +
            "if count == tonumber(ARGV[1]) then " +
            "    redis.call('EXPIRE', KEYS[1], ARGV[2]) " +
            "end " +
            "return count"
        );

        CHECK_DEL_SCRIPT = new DefaultRedisScript<>();
        CHECK_DEL_SCRIPT.setResultType(Long.class);
        CHECK_DEL_SCRIPT.setScriptText(
            "local code = redis.call('GET', KEYS[1]) " +
            "if code == false then return 0 end " +
            "if code ~= ARGV[1] then return -1 end " +
            "redis.call('DEL', KEYS[1]) " +
            "return 1"
        );

        // 秒杀: SISMEMBER防重复 → 库存检查 → DECR扣库存 → SADD记录
        SECKILL_LUA_SCRIPT = new DefaultRedisScript<>();
        SECKILL_LUA_SCRIPT.setResultType(Long.class);
        SECKILL_LUA_SCRIPT.setScriptText(
            "if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then " +
            "    return -1 " +
            "end " +
            "local stock = tonumber(redis.call('GET', KEYS[1]) or '0') " +
            "if stock <= 0 then " +
            "    return 0 " +
            "end " +
            "redis.call('DECR', KEYS[1]) " +
            "redis.call('SADD', KEYS[2], ARGV[1]) " +
            "return 1"
        );

        // 秒杀库存回滚: INCR恢复库存 + SREM移除用户记录
        SECKILL_ROLLBACK_LUA_SCRIPT = new DefaultRedisScript<>();
        SECKILL_ROLLBACK_LUA_SCRIPT.setResultType(Long.class);
        SECKILL_ROLLBACK_LUA_SCRIPT.setScriptText(
            "redis.call('INCR', KEYS[1]) " +
            "redis.call('SREM', KEYS[2], ARGV[1]) " +
            "return 1"
        );
    }

    // ========== String 操作（StringRedisTemplate） ==========

    public void setString(String key, String value, long timeout, TimeUnit unit) {
        stringRedisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    /** 持久化写入 String，不设 TTL（除非需要覆盖已有 TTL） */
    public void setStringPersist(String key, String value) {
        stringRedisTemplate.opsForValue().set(key, value);
    }

    public String getString(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /** SET NX（仅当 key 不存在时设置），返回 true 表示设置成功 */
    public Boolean setIfAbsent(String key, String value, long timeout, TimeUnit unit) {
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeout, unit);
        return result != null && result;
    }

    // ========== 原子计数器 ==========

    public Long increment(String key, long delta) {
        return stringRedisTemplate.opsForValue().increment(key, delta);
    }

    public Long incrementWithExpire(String key, long delta, long timeout, TimeUnit unit) {
        return stringRedisTemplate.execute(
                INCR_WITH_EXPIRE_SCRIPT,
                Collections.singletonList(key),
                String.valueOf(delta),
                String.valueOf(unit.toSeconds(timeout))
        );
    }

    // ========== Hash 操作（RedisTemplate，支持 Object 值序列化） ==========

    public void putAllHash(String key, Map<String, Object> map) {
        redisTemplate.opsForHash().putAll(key, map);
    }

    /** 设置 Hash 中单个字段 */
    public void putHash(String key, String field, Object value) {
        redisTemplate.opsForHash().put(key, field, value);
    }

    public Map<Object, Object> entriesHash(String key) {
        return redisTemplate.opsForHash().entries(key);
    }

    // ========== Set 操作（Token 反向索引） ==========

    public Long sadd(String key, String value) {
        return stringRedisTemplate.opsForSet().add(key, value);
    }

    public Long srem(String key, String value) {
        return stringRedisTemplate.opsForSet().remove(key, value);
    }

    public Set<String> smembers(String key) {
        return stringRedisTemplate.opsForSet().members(key);
    }

    /** SCARD — 返回 Set 的元素个数（不加载全量数据） */
    public Long scard(String key) {
        return stringRedisTemplate.opsForSet().size(key);
    }

    /** 求两个 Set 的交集 */
    public Set<String> sinter(String key1, String key2) {
        return stringRedisTemplate.opsForSet().intersect(key1, key2);
    }

    // ========== 原子校验（Lua） ==========

    /** @return 1=匹配并已删除, 0=key不存在, -1=不匹配 */
    public Long checkAndDelete(String key, String expectedCode) {
        return stringRedisTemplate.execute(
                CHECK_DEL_SCRIPT,
                Collections.singletonList(key),
                expectedCode
        );
    }

    // ========== 通用操作 ==========

    public void delete(String key) {
        stringRedisTemplate.delete(key);
    }

    public void delete(Collection<String> keys) {
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    /**
     * 使用 SCAN 命令安全遍历 keys（生产安全，避免 KEYS 阻塞）。
     */
    public Set<String> scanKeys(String keyPattern) {
        Set<String> keys = new HashSet<>();
        streamStringRedisTemplate.execute((RedisCallback<Void>) connection -> {
            try (org.springframework.data.redis.core.Cursor<byte[]> cursor =
                         connection.scan(org.springframework.data.redis.core.ScanOptions.scanOptions()
                                 .match(keyPattern).count(100).build())) {
                while (cursor.hasNext()) {
                    keys.add(new String(cursor.next(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                log.warn("Redis SCAN failed, pattern={}", keyPattern, e);
            }
            return null;
        });
        return keys;
    }

    public Boolean exists(String key) {
        return stringRedisTemplate.hasKey(key);
    }

    // ========== 通用对象操作（Jackson 序列化，用于业务缓存） ==========

    public void setObject(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    public <T> T getObject(String key, Class<T> clazz) {
        Object value = redisTemplate.opsForValue().get(key);
        if (value == null) {
            return null;
        }
        if (clazz.isInstance(value)) {
            return clazz.cast(value);
        }
        return null;
    }

    public void expire(String key, long timeout, TimeUnit unit) {
        stringRedisTemplate.expire(key, timeout, unit);
    }

    public void expireIfLessThan(String key, long thresholdSeconds, long timeout, TimeUnit unit) {
        Long remain = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
        if (remain != null && remain < thresholdSeconds) {
            stringRedisTemplate.expire(key, timeout, unit);
        }
    }

    public static long secondsUntilMidnight() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime midnight = now.toLocalDate().plusDays(1).atStartOfDay();
        return ChronoUnit.SECONDS.between(now, midnight);
    }

    // ========== 秒杀Lua ==========
    public Long executeSeckillLua(Long voucherId, Long userId) {
        List<String> keys = new ArrayList<>();
        keys.add(SeckillConstants.SECKILL_STOCK_KEY + voucherId);
        keys.add(SeckillConstants.SECKILL_ORDER_KEY + voucherId);
        return stringRedisTemplate.execute(SECKILL_LUA_SCRIPT, keys, userId.toString());
    }

    /**
     * 回滚秒杀库存: INCR恢复库存 + SREM移除用户购买记录。
     * 当Stream推送失败时调用，确保已扣库存能恢复。
     */
    public Long rollbackSeckillLua(Long voucherId, Long userId) {
        List<String> keys = new ArrayList<>();
        keys.add(SeckillConstants.SECKILL_STOCK_KEY + voucherId);
        keys.add(SeckillConstants.SECKILL_ORDER_KEY + voucherId);
        return stringRedisTemplate.execute(SECKILL_ROLLBACK_LUA_SCRIPT, keys, userId.toString());
    }

    // ========== Redis Stream 操作 (XADD/XREADGROUP/XACK/XPENDING/XCLAIM/XDEL) ==========
    // ★ Stream 消费者使用独立连接，避免 XREADGROUP BLOCK 阻塞主连接池

    public String addToStream(String streamKey, Map<String, String> fields) {
        return streamStringRedisTemplate.execute((RedisCallback<String>) connection -> {
            List<byte[]> args = new ArrayList<>();
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                args.add(entry.getKey().getBytes(StandardCharsets.UTF_8));
                args.add(entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
            byte[] rawKey = streamKey.getBytes(StandardCharsets.UTF_8);
            Object execResult = connection.execute("XADD", buildXAddArgs(rawKey, "*", args));
            if (execResult == null) {
                return null;
            }
            return new String((byte[]) execResult, StandardCharsets.UTF_8);
        });
    }

    /**
     * 创建 Redis Stream 消费者组。
     * 使用 XGROUP CREATE 原生命令。
     * 如果组已存在 (BUSYGROUP) 则正常返回。
     */
    public String createConsumerGroup(String streamKey, String groupName) {
        try {
            streamStringRedisTemplate.execute((RedisCallback<String>) connection -> {
                byte[] rawKey = streamKey.getBytes(StandardCharsets.UTF_8);
                byte[] rawGroup = groupName.getBytes(StandardCharsets.UTF_8);
                byte[] mkstream = "MKSTREAM".getBytes(StandardCharsets.UTF_8);
                byte[] zero = "0".getBytes(StandardCharsets.UTF_8);
                connection.execute("XGROUP", "CREATE".getBytes(StandardCharsets.UTF_8),
                        rawKey, rawGroup, zero, mkstream);
                return null;
            });
            return "OK";
        } catch (Exception e) {
            String msg = e.getMessage();
            Throwable cause = e.getCause();
            String causeMsg = cause != null ? cause.getMessage() : null;
            if ((msg != null && msg.contains("BUSYGROUP"))
                    || (causeMsg != null && causeMsg.contains("BUSYGROUP"))) {
                return "OK";  // 组已存在，正常
            }
            log.warn("Failed to create consumer group, stream={}, group={}: {}",
                    streamKey, groupName, msg != null ? msg : "unknown");
            return null;
        }
    }

    // ========== Redis Stream 原生 Lettuce 操作 ==========
    // StringRedisTemplate 的 connection.execute() 无法正确处理 Stream 命令的嵌套/混合类型响应。
    // 因此 XREADGROUP / XPENDING / XCLAIM 走原生 Lettuce 同步连接。
    // 注意：LettuceConnectionFactory 底层使用 ByteArrayCodec，所以必须传 byte[] 参数，
    // 返回值也要从 byte[] 转回 String。

    @SuppressWarnings("unchecked")
    private io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> nativeSync(
            org.springframework.data.redis.connection.lettuce.LettuceConnection conn) {
        Object nc = conn.getNativeConnection();
        if (nc instanceof io.lettuce.core.api.StatefulRedisConnection) {
            return ((io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]>) nc).sync();
        }
        // setShareNativeConnection(false) → native connection 为 RedisAsyncCommandsImpl
        try {
            java.lang.reflect.Method m = nc.getClass().getMethod("getStatefulConnection");
            Object sc = m.invoke(nc);
            if (sc instanceof io.lettuce.core.api.StatefulRedisConnection) {
                return ((io.lettuce.core.api.StatefulRedisConnection<byte[], byte[]>) sc).sync();
            }
        } catch (Exception e) {
            log.warn("无法从 {} 获取 StatefulRedisConnection", nc.getClass().getName(), e);
        }
        return null;
    }

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String s(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** StreamMessage 列表 → List<Map> 转换（getId() 固定返回 String） */
    private static List<Map<String, String>> toStreamResult(
            List<io.lettuce.core.StreamMessage<byte[], byte[]>> msgs) {
        List<Map<String, String>> result = new ArrayList<>();
        for (io.lettuce.core.StreamMessage<byte[], byte[]> msg : msgs) {
            Map<String, String> map = new HashMap<>();
            map.put("_recordId", msg.getId()); // getId() always returns String
            Map<byte[], byte[]> body = msg.getBody();
            for (Map.Entry<byte[], byte[]> e : body.entrySet()) {
                map.put(s(e.getKey()), s(e.getValue()));
            }
            result.add(map);
        }
        return result;
    }

    public List<Map<String, String>> readFromStream(String streamKey, String groupName,
                                                     String consumerName, int count, long blockMs) {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) streamStringRedisTemplate.getConnectionFactory();
        org.springframework.data.redis.connection.lettuce.LettuceConnection conn =
                (org.springframework.data.redis.connection.lettuce.LettuceConnection) factory.getConnection();
        try {
            io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> sync = nativeSync(conn);
            if (sync == null) {
                return Collections.emptyList();
            }
            io.lettuce.core.Consumer<byte[]> consumer =
                    io.lettuce.core.Consumer.from(b(groupName), b(consumerName));
            io.lettuce.core.XReadArgs args = io.lettuce.core.XReadArgs.Builder.block(blockMs).count(count);
            io.lettuce.core.XReadArgs.StreamOffset<byte[]> offset =
                    io.lettuce.core.XReadArgs.StreamOffset.from(b(streamKey), ">");

            List<io.lettuce.core.StreamMessage<byte[], byte[]>> msgs =
                    sync.xreadgroup(consumer, args, offset);
            if (msgs == null || msgs.isEmpty()) {
                return Collections.emptyList();
            }
            return toStreamResult(msgs);
        } finally {
            conn.close();
        }
    }

    /**
     * XPENDING 查询 PENDING 消息列表。
     * 返回格式: {recordId, consumer, idleMs, deliveryCount}
     */
    public List<Map<String, Object>> pendingStream(String streamKey, String groupName,
                                                    String startId, String endId, int count) {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) streamStringRedisTemplate.getConnectionFactory();
        org.springframework.data.redis.connection.lettuce.LettuceConnection conn =
                (org.springframework.data.redis.connection.lettuce.LettuceConnection) factory.getConnection();
        try {
            io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> sync = nativeSync(conn);
            if (sync == null) {
                return Collections.emptyList();
            }
            List<Object> pending =
                    sync.xpending(b(streamKey), b(groupName),
                            io.lettuce.core.Range.create(startId, endId),
                            io.lettuce.core.Limit.create(0, count));
            List<Map<String, Object>> result = new ArrayList<>();
            if (pending == null) {
                return result;
            }
            for (io.lettuce.core.models.stream.PendingMessage pm : pending) {
                Map<String, Object> map = new HashMap<>();
                map.put("recordId", pm.getId());
                map.put("consumer", pm.getConsumer());
                map.put("idleMs", pm.getMsSinceLastDelivery());
                map.put("deliveryCount", (long) pm.getRedeliveryCount());
                result.add(map);
            }
            return result;
        } finally {
            conn.close();
        }
    }

    public List<Map<String, String>> claimStream(String streamKey, String groupName,
                                                  String consumerName, long minIdleMs,
                                                  String... recordIds) {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) streamStringRedisTemplate.getConnectionFactory();
        org.springframework.data.redis.connection.lettuce.LettuceConnection conn =
                (org.springframework.data.redis.connection.lettuce.LettuceConnection) factory.getConnection();
        try {
            io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> sync = nativeSync(conn);
            if (sync == null) {
                return Collections.emptyList();
            }
            io.lettuce.core.Consumer<byte[]> consumer =
                    io.lettuce.core.Consumer.from(b(groupName), b(consumerName));
            io.lettuce.core.XClaimArgs claimArgs =
                    io.lettuce.core.XClaimArgs.Builder.minIdleTime(minIdleMs);
            List<io.lettuce.core.StreamMessage<byte[], byte[]>> msgs =
                    sync.xclaim(b(streamKey), consumer, claimArgs, recordIds);
            if (msgs == null || msgs.isEmpty()) {
                return Collections.emptyList();
            }
            return toStreamResult(msgs);
        } finally {
            conn.close();
        }
    }

    public Long ackStream(String streamKey, String groupName, String... recordIds) {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) streamStringRedisTemplate.getConnectionFactory();
        org.springframework.data.redis.connection.lettuce.LettuceConnection conn =
                (org.springframework.data.redis.connection.lettuce.LettuceConnection) factory.getConnection();
        try {
            io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> sync = nativeSync(conn);
            if (sync == null) {
                return 0L;
            }
            return sync.xack(b(streamKey), b(groupName), recordIds);
        } finally {
            conn.close();
        }
    }

    public Long deleteStreamMessage(String streamKey, String... recordIds) {
        org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory factory =
                (org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory) streamStringRedisTemplate.getConnectionFactory();
        org.springframework.data.redis.connection.lettuce.LettuceConnection conn =
                (org.springframework.data.redis.connection.lettuce.LettuceConnection) factory.getConnection();
        try {
            io.lettuce.core.api.sync.RedisCommands<byte[], byte[]> sync = nativeSync(conn);
            if (sync == null) {
                return 0L;
            }
            return sync.xdel(b(streamKey), recordIds);
        } finally {
            conn.close();
        }
    }

    public Long deleteStream(String streamKey) {
        return streamStringRedisTemplate.execute((RedisCallback<Long>) connection ->
                connection.del(streamKey.getBytes(StandardCharsets.UTF_8)));
    }

    private byte[][] buildXAddArgs(byte[] rawKey, String wildcard, List<byte[]> fields) {
        List<byte[]> args = new ArrayList<>();
        args.add(rawKey);
        args.add(wildcard.getBytes(StandardCharsets.UTF_8));
        args.addAll(fields);
        return args.toArray(new byte[0][]);
    }

    // ==================== HyperLogLog 操作 ====================

    /**
     * PFADD — 向 HyperLogLog 添加元素（自动去重）
     * @return 1=基数变化, 0=未变化
     */
    public Long pfadd(String key, String... values) {
        return stringRedisTemplate.opsForHyperLogLog().add(key, values);
    }

    /**
     * PFCOUNT — 获取 HyperLogLog 的近似基数
     */
    public Long pfcount(String... keys) {
        return stringRedisTemplate.opsForHyperLogLog().size(keys);
    }

    /**
     * PFMERGE — 合并多个 HyperLogLog 到目标 key
     */
    public Long pfmerge(String destKey, String... sourceKeys) {
        return stringRedisTemplate.opsForHyperLogLog().union(destKey, sourceKeys);
    }

    // ==================== GEO 操作 ====================

    /**
     * GEOADD — 添加单个地理位置
     */
    public Long geoAdd(String key, double x, double y, String member) {
        return stringRedisTemplate.opsForGeo().add(key, new org.springframework.data.geo.Point(x, y), member);
    }

    /**
     * GEOADD — 批量添加地理位置
     */
    public Long geoAddBatch(String key, Map<String, org.springframework.data.geo.Point> members) {
        return stringRedisTemplate.opsForGeo().add(key, members);
    }

    /**
     * GEOSEARCH — 按距离搜索附近成员（由近到远）。
     * 使用 radius() API，兼容 Spring Data Redis 2.3.x。
     *
     * @param key GEO key
     * @param x 经度
     * @param y 纬度
     * @param radiusKm 搜索半径（公里）
     * @param limit 返回数量上限
     * @return GeoResults 包含成员名和距离
     */
    public org.springframework.data.geo.GeoResults<org.springframework.data.redis.connection.RedisGeoCommands.GeoLocation<String>> geoSearch(
            String key, double x, double y, double radiusKm, long limit) {
        org.springframework.data.geo.Point center = new org.springframework.data.geo.Point(x, y);
        org.springframework.data.geo.Distance radius = new org.springframework.data.geo.Distance(radiusKm, org.springframework.data.geo.Metrics.KILOMETERS);
        org.springframework.data.geo.Circle circle = new org.springframework.data.geo.Circle(center, radius);
        org.springframework.data.redis.core.GeoOperations<String, String> geoOps = stringRedisTemplate.opsForGeo();
        // Spring Data Redis 2.3.x: 使用 radius() 替代 search(GeoReference)
        org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs args =
                org.springframework.data.redis.connection.RedisGeoCommands.GeoRadiusCommandArgs
                        .newGeoRadiusArgs().includeDistance().sortAscending().limit(limit);
        return geoOps.radius(key, circle, args);
    }
}
