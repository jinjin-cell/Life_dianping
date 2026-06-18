package com.lifedp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 秒杀系统全维度指标收集与智能告警。
 *
 * 采集: 请求/成功/库存不足/重复/限流/异常/熔断/Stream投递消费重试DLQ/回滚失败/响应时间P50/P99
 * 定时: 每60s Redis持久化快照, 每30s告警自检, 每5min库存对账
 */
@Slf4j
@Component
@EnableScheduling
public class SeckillMetrics {

    @Resource
    private RedisHelper redisHelper;

    @Resource
    private SeckillCircuitBreaker circuitBreaker;

    private static final String METRICS_PREFIX = "metrics:seckill:";
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder successCount = new LongAdder();
    private final LongAdder stockOutCount = new LongAdder();
    private final LongAdder duplicateCount = new LongAdder();
    private final LongAdder rateLimitCount = new LongAdder();
    private final LongAdder errorCount = new LongAdder();
    private final LongAdder circuitOpenCount = new LongAdder();
    private final LongAdder rollbackFailedCount = new LongAdder();
    private final LongAdder streamPublishCount = new LongAdder();
    private final LongAdder streamConsumeCount = new LongAdder();
    private final LongAdder streamRetryCount = new LongAdder();
    private final LongAdder dlqCount = new LongAdder();
    private final LongAdder degradedCount = new LongAdder();

    private final ConcurrentHashMap<String, Long> responseTimeWindow = new ConcurrentHashMap<>();
    private final AtomicLong responseTimeSum = new AtomicLong(0);
    private final AtomicLong responseTimeCount = new AtomicLong(0);

    @PostConstruct
    public void init() {
        redisHelper.setString(METRICS_PREFIX + "start_time",
                DT_FMT.format(LocalDateTime.now()), 86400, TimeUnit.SECONDS);
    }

    public void recordRequest() { totalRequests.increment(); }
    public void recordSuccess() { successCount.increment(); }
    public void recordStockOut() { stockOutCount.increment(); }
    public void recordDuplicate() { duplicateCount.increment(); }
    public void recordRateLimit() { rateLimitCount.increment(); }
    public void recordError() { errorCount.increment(); }
    public void recordCircuitOpen() { circuitOpenCount.increment(); }
    public void recordRollbackFailed() { rollbackFailedCount.increment(); }
    public void recordStreamPublish() { streamPublishCount.increment(); }
    public void recordStreamConsume() { streamConsumeCount.increment(); }
    public void recordStreamRetry() { streamRetryCount.increment(); }
    public void recordDlq() { dlqCount.increment(); }

    public void recordResponseTime(long voucherId, long responseTimeMs) {
        responseTimeWindow.put("v:" + voucherId, responseTimeMs);
        responseTimeSum.addAndGet(responseTimeMs);
        responseTimeCount.incrementAndGet();
    }

    public long getAvgResponseTime() {
        long count = responseTimeCount.get();
        return count == 0 ? 0 : responseTimeSum.get() / count;
    }

    public Map<String, Object> getMetrics() {
        Map<String, Object> m = new ConcurrentHashMap<>();
        m.put("totalRequests", totalRequests.sum());
        m.put("successCount", successCount.sum());
        m.put("stockOutCount", stockOutCount.sum());
        m.put("duplicateCount", duplicateCount.sum());
        m.put("rateLimitCount", rateLimitCount.sum());
        m.put("errorCount", errorCount.sum());
        m.put("circuitOpenCount", circuitOpenCount.sum());
        m.put("circuitState", circuitBreaker.getState());
        m.put("rollbackFailedCount", rollbackFailedCount.sum());
        m.put("streamPublishCount", streamPublishCount.sum());
        m.put("streamConsumeCount", streamConsumeCount.sum());
        m.put("streamRetryCount", streamRetryCount.sum());
        m.put("dlqCount", dlqCount.sum());
        m.put("degradedCount", degradedCount.sum());
        m.put("successRate", calculateSuccessRate());
        m.put("avgResponseTimeMs", getAvgResponseTime());

        String startTime = redisHelper.getString(METRICS_PREFIX + "start_time");
        m.put("startTime", startTime != null ? startTime : "N/A");

        return m;
    }

    private String calculateSuccessRate() {
        long total = totalRequests.sum();
        if (total == 0) return "0.00%";
        double rate = (double) successCount.sum() / total * 100;
        return String.format("%.2f%%", rate);
    }

    @Scheduled(fixedRate = 60000)
    public void flushMetricsToRedis() {
        try {
            Map<String, Object> snapshot = getMetrics();
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                redisHelper.setString(METRICS_PREFIX + entry.getKey(),
                        String.valueOf(entry.getValue()), 120, TimeUnit.SECONDS);
            }
            redisHelper.setString(METRICS_PREFIX + "last_flush",
                    DT_FMT.format(LocalDateTime.now()), 120, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Metrics flush to Redis failed", e);
        }
    }

    @Scheduled(fixedRate = 30000)
    public void checkAndAlert() {
        long total = totalRequests.sum();
        long errors = errorCount.sum();
        if (total > 0) {
            double errRate = (double) errors * 100.0 / total;
            if (errRate > 5.0) {
                log.warn("[ALERT] 错误率: {}% (errors={}, total={})",
                        String.format("%.2f", errRate), errors, total);
            }
        }

        if (circuitBreaker.isOpen()) {
            log.error("[ALERT] Redis熔断器已打开! 所有Redis请求被降级");
        }

        if (rollbackFailedCount.sum() > 0) {
            log.error("[ALERT] 库存回滚失败次数: {}, 需人工补偿检查",
                    rollbackFailedCount.sum());
        }

        if (dlqCount.sum() > 10) {
            log.warn("[ALERT] DLQ死信队列堆积: {} 条消息待人工处理", dlqCount.sum());
        }

        try {
            Boolean redisOk = redisHelper.exists(METRICS_PREFIX + "start_time");
            if (redisOk == null || !redisOk) {
                log.error("[ALERT] Redis 连接异常");
            }
        } catch (Exception e) {
            log.error("[ALERT] Redis 连接异常: {}", e.getMessage());
        }
    }

    @Scheduled(fixedRate = 300000)
    public void reconcileStock() {
        try {
            // 扫描所有秒杀库存key，逐个对账
            Set<String> stockKeys = redisHelper.scanKeys(SeckillConstants.SECKILL_STOCK_KEY + "*");
            if (stockKeys == null || stockKeys.isEmpty()) return;
            for (String stockKey : stockKeys) {
                String voucherId = stockKey.substring(SeckillConstants.SECKILL_STOCK_KEY.length());
                String stockStr = redisHelper.getString(stockKey);
                if (stockStr == null) continue;
                int redisStock = Integer.parseInt(stockStr);
                String orderKey = SeckillConstants.SECKILL_ORDER_KEY + voucherId;
                Long orderSetSize = redisHelper.smembers(orderKey) != null
                        ? (long) redisHelper.smembers(orderKey).size() : 0;
                int bought = orderSetSize.intValue();
                if (redisStock < 0 || redisStock + bought > 10000) {
                    log.warn("[ALERT] 库存偏差: voucher={}, Redis库存={}, 购买人数={}",
                            voucherId, redisStock, bought);
                }
            }
        } catch (Exception e) {
            log.warn("库存对账异常", e);
        }
    }

    public void reset() {
        totalRequests.reset();
        successCount.reset();
        stockOutCount.reset();
        duplicateCount.reset();
        rateLimitCount.reset();
        errorCount.reset();
        circuitOpenCount.reset();
        rollbackFailedCount.reset();
        streamPublishCount.reset();
        streamConsumeCount.reset();
        streamRetryCount.reset();
        dlqCount.reset();
        degradedCount.reset();
        responseTimeSum.set(0);
        responseTimeCount.set(0);
        responseTimeWindow.clear();
        redisHelper.setString(METRICS_PREFIX + "start_time",
                DT_FMT.format(LocalDateTime.now()), 86400, TimeUnit.SECONDS);
    }
}
