package com.lifereviewing.utils;

public class SeckillConstants {
    // Redis缓存Key
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String SECKILL_STOCK_INIT_KEY = "seckill:stock:init:"; // 初始库存(无TTL, 懒加载对账基准)
    public static final String SECKILL_ORDER_KEY = "seckill:order:";       // SET: 已购买用户ID
    // Redis Stream 消息队列
    public static final String SECKILL_STREAM_KEY = "seckill:order:stream";
    public static final String SECKILL_STREAM_GROUP = "order-consumer-group";
    public static final String SECKILL_STREAM_CONSUMER = "order-consumer-1";
    public static final String SECKILL_DLQ_STREAM_KEY = "seckill:order:dlq"; // 死信队列
    // 限流
    public static final String SECKILL_RATE_LIMIT_KEY = "seckill:rate:";
    public static final String SECKILL_GLOBAL_RATE_KEY = "seckill:rate:global";
    // 分布式锁 & 重试
    public static final String SECKILL_LOCK_KEY = "lock:seckill:order:";
    public static final String SECKILL_ORDER_ID_KEY = "seckill:order:id";    // 全局订单ID生成器
    public static final String SECKILL_RETRY_KEY = "seckill:retry:";
    // 限流参数
    public static final Long SECKILL_RATE_LIMIT_WINDOW = 1L;
    public static final int SECKILL_RATE_LIMIT_MAX = 5;                     // 每用户每秒最多5次
    public static final int SECKILL_GLOBAL_RATE_MAX = 10000;                // 全局每秒最多10000次
    // 消息重试参数
    public static final int STREAM_MAX_RETRIES = 3;                         // 最大重试次数
    public static final Long SECKILL_ORDER_LOCK_TTL = 5L;
    public static final long PENDING_CHECK_INTERVAL_MS = 30000L;            // PENDING扫描间隔30s
    public static final long PENDING_CLAIM_MIN_IDLE_MS = 30000L;            // 消息闲置超过30s才Claim
}
