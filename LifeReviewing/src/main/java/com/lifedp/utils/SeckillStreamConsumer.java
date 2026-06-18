package com.lifedp.utils;

import com.lifedp.entity.VoucherOrder;
import com.lifedp.mapper.VoucherOrderMapper;
import com.lifedp.service.ISeckillVoucherService;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lifedp.entity.SeckillVoucher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Redis Stream 异步订单消费者。
 *
 * 消息生命周期:
 *   生产者投递 → Stream队列 → 线程1 XREADGROUP消费 → 落DB → XACK确认
 *                              ↓ 消费失败(未ACK)
 *                         PENDING列表 → 线程2每30s扫描 → XCLAIM认领重试
 *                              ↓ 重试超3次
 *                         移入DLQ死信队列 → 人工介入
 *
 * 幂等性保证: SETNX分布式锁 + DB订单查重，确保同一条消息不被重复落库。
 */
@Slf4j
@Component
public class SeckillStreamConsumer implements CommandLineRunner {

    @Resource
    private RedisHelper redisHelper;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SeckillMetrics metrics;

    // 双线程: 线程1消费新消息, 线程2兜底处理PENDING消息

    private final ExecutorService newMessageExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seckill-stream-new");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService pendingExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seckill-stream-pending");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean running = true;

    @Override
    public void run(String... args) {
        initConsumerGroup();
        newMessageExecutor.submit(this::consumeNewMessages);
        pendingExecutor.submit(this::handlePendingMessages);
    }

    private void initConsumerGroup() {
        redisHelper.createConsumerGroup(SeckillConstants.SECKILL_STREAM_KEY,
                SeckillConstants.SECKILL_STREAM_GROUP);
        redisHelper.createConsumerGroup(SeckillConstants.SECKILL_DLQ_STREAM_KEY,
                SeckillConstants.SECKILL_STREAM_GROUP);
    }

    // 阻塞读取新消息 (XREADGROUP BLOCK 2000ms)
    private void consumeNewMessages() {
        while (running) {
            try {
                List<Map<String, String>> messages = redisHelper.readFromStream(
                        SeckillConstants.SECKILL_STREAM_KEY,
                        SeckillConstants.SECKILL_STREAM_GROUP,
                        SeckillConstants.SECKILL_STREAM_CONSUMER,
                        1, 2000L);

                if (messages == null || messages.isEmpty()) {
                    continue;
                }

                for (Map<String, String> msg : messages) {
                    processMessage(msg, false);
                }
            } catch (Exception e) {
                String msg = e.getMessage();
                // 消费者组不存在 → 尝试重建
                if (msg != null && msg.contains("NOGROUP")) {
                    log.warn("Consumer group missing, reinitializing...");
                    initConsumerGroup();
                    sleepUninterrupted(1000);
                } else {
                    log.error("Stream consumer error in new message loop", e);
                    sleepUninterrupted(1000);
                }
            }
        }
    }

    // 每30s扫描PENDING消息: 超30s空闲则Claim重试, 超过3次移入DLQ
    private void handlePendingMessages() {
        while (running) {
            try {
                sleepUninterrupted(SeckillConstants.PENDING_CHECK_INTERVAL_MS);

                if (!running) {
                    break;
                }

                List<Map<String, Object>> pendingList = redisHelper.pendingStream(
                        SeckillConstants.SECKILL_STREAM_KEY,
                        SeckillConstants.SECKILL_STREAM_GROUP,
                        "-", "+", 50);

                if (pendingList == null || pendingList.isEmpty()) {
                    continue;
                }

                for (Map<String, Object> pending : pendingList) {
                    String recordId = (String) pending.get("recordId");
                    Object idleMsObj = pending.get("idleMs");
                    long idleMs = idleMsObj instanceof Long ? (Long) idleMsObj : 0L;
                    Object deliveryCountObj = pending.get("deliveryCount");
                    int deliveryCount = deliveryCountObj instanceof Long
                            ? ((Long) deliveryCountObj).intValue() : 1;

                    if (idleMs < SeckillConstants.PENDING_CLAIM_MIN_IDLE_MS) {
                        continue;
                    }

                    if (deliveryCount >= SeckillConstants.STREAM_MAX_RETRIES) {
                        moveToDLQAndAck(recordId);
                        metrics.recordDlq();
                        continue;
                    }

                    List<Map<String, String>> claimed = redisHelper.claimStream(
                            SeckillConstants.SECKILL_STREAM_KEY,
                            SeckillConstants.SECKILL_STREAM_GROUP,
                            SeckillConstants.SECKILL_STREAM_CONSUMER,
                            SeckillConstants.PENDING_CLAIM_MIN_IDLE_MS,
                            recordId);

                    if (claimed != null && !claimed.isEmpty()) {
                        metrics.recordStreamRetry();
                        for (Map<String, String> msg : claimed) {
                            processMessage(msg, true);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Stream consumer error in pending handler loop", e);
            }
        }
    }

    private void moveToDLQAndAck(String recordId) {
        List<Map<String, String>> claimed = redisHelper.claimStream(
                SeckillConstants.SECKILL_STREAM_KEY,
                SeckillConstants.SECKILL_STREAM_GROUP,
                SeckillConstants.SECKILL_STREAM_CONSUMER,
                0,
                recordId);

        if (claimed == null || claimed.isEmpty()) {
            return;
        }

        Map<String, String> msg = claimed.get(0);
        String orderId = msg.get("orderId");
        String voucherId = msg.get("voucherId");
        String userId = msg.get("userId");

        Map<String, String> dlqFields = new java.util.HashMap<>();
        if (orderId != null) dlqFields.put("orderId", orderId);
        if (voucherId != null) dlqFields.put("voucherId", voucherId);
        if (userId != null) dlqFields.put("userId", userId);
        dlqFields.put("_recordId", recordId);

        try {
            redisHelper.addToStream(SeckillConstants.SECKILL_DLQ_STREAM_KEY, dlqFields);
            redisHelper.ackStream(SeckillConstants.SECKILL_STREAM_KEY,
                    SeckillConstants.SECKILL_STREAM_GROUP, recordId);
            redisHelper.delete(SeckillConstants.SECKILL_RETRY_KEY + orderId);
            log.warn("Message moved to DLQ, orderId={}", orderId);
        } catch (Exception e) {
            log.error("DLQ写入或ACK失败, orderId={}, recordId={}, 消息将留在PENDING等待下次重试",
                    orderId, recordId, e);
        }
    }

    /**
     * 消息处理: 重试次数检查 → SETNX分布式锁 → DB查重 → insert订单 → ACK确认
     * DB写入失败时不ACK，让消息留在PENDING等待重试。
     */
    // 分布式锁(SETNX) + DB查重保证消费幂等性
    private void processMessage(Map<String, String> msg, boolean isRetry) {
        String recordId = msg.get("_recordId");
        String orderIdStr = msg.get("orderId");
        String voucherIdStr = msg.get("voucherId");
        String userIdStr = msg.get("userId");

        if (orderIdStr == null || voucherIdStr == null || userIdStr == null) {
            log.error("Stream message missing required fields, recordId={}", recordId);
            redisHelper.ackStream(SeckillConstants.SECKILL_STREAM_KEY,
                    SeckillConstants.SECKILL_STREAM_GROUP, recordId);
            return;
        }

        long orderId;
        long voucherId;
        long userId;
        try {
            orderId = Long.parseLong(orderIdStr);
            voucherId = Long.parseLong(voucherIdStr);
            userId = Long.parseLong(userIdStr);
        } catch (NumberFormatException e) {
            log.error("Stream message has invalid numeric fields, recordId={}, orderId={}, voucherId={}, userId={}",
                    recordId, orderIdStr, voucherIdStr, userIdStr, e);
            redisHelper.ackStream(SeckillConstants.SECKILL_STREAM_KEY,
                    SeckillConstants.SECKILL_STREAM_GROUP, recordId);
            return;
        }

        // 从Redis读取当前重试次数，防无限重试
        String retryKey = SeckillConstants.SECKILL_RETRY_KEY + orderId;
        int retryCount = 0;
        String retryCountStr = redisHelper.getString(retryKey);
        if (retryCountStr != null) {
            retryCount = Integer.parseInt(retryCountStr);
        }

        if (retryCount >= SeckillConstants.STREAM_MAX_RETRIES) {
            log.warn("Message reached max retries, moving to DLQ, orderId={}", orderId);
            moveToDLQAndAck(recordId);
            return;
        }

        // 获取分布式锁，防止并发消费同一订单
        String lockKey = SeckillConstants.SECKILL_LOCK_KEY + orderId;
        Boolean locked = redisHelper.setIfAbsent(lockKey, "1",
                SeckillConstants.SECKILL_ORDER_LOCK_TTL, TimeUnit.SECONDS);
        if (locked == null || !locked) {
            // 已被其他消费者处理，直接ACK
            redisHelper.ackStream(SeckillConstants.SECKILL_STREAM_KEY,
                    SeckillConstants.SECKILL_STREAM_GROUP, recordId);
            return;
        }

        // DB查重：订单已存在则跳过
        VoucherOrder existing = voucherOrderMapper.selectById(orderId);
        if (existing != null) {
            redisHelper.ackStream(SeckillConstants.SECKILL_STREAM_KEY,
                    SeckillConstants.SECKILL_STREAM_GROUP, recordId);
            redisHelper.delete(lockKey);
            return;
        }

        try {
            VoucherOrder order = new VoucherOrder();
            order.setId(orderId);
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            order.setStatus(1);
            order.setCreateTime(LocalDateTime.now());
            voucherOrderMapper.insert(order);

            // 同步扣减 DB 库存，防止 Redis key 过期后懒加载读到原始库存导致超卖
            boolean stockDeducted = seckillVoucherService.update(
                    new LambdaUpdateWrapper<SeckillVoucher>()
                            .eq(SeckillVoucher::getVoucherId, voucherId)
                            .gt(SeckillVoucher::getStock, 0)
                            .setSql("stock = stock - 1"));
            if (!stockDeducted) {
                log.warn("DB库存扣减跳过(stock已为0或不存在), voucherId={}, orderId={}, 可能存在Redis-DB库存分叉",
                        voucherId, orderId);
            }

            // 落库成功：ACK + 清理重试计数
            redisHelper.ackStream(SeckillConstants.SECKILL_STREAM_KEY,
                    SeckillConstants.SECKILL_STREAM_GROUP, recordId);
            redisHelper.delete(retryKey);
            metrics.recordStreamConsume();
        } catch (Exception e) {
            // 落库失败：不ACK，记录重试次数，等待下次Claim重试
            log.error("Failed to create order in DB, orderId={}", orderId, e);
            redisHelper.setString(retryKey, String.valueOf(retryCount + 1),
                    600, TimeUnit.SECONDS);
        } finally {
            redisHelper.delete(lockKey);
        }
    }

    private void sleepUninterrupted(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        newMessageExecutor.shutdown();
        pendingExecutor.shutdown();
        try {
            if (!newMessageExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                newMessageExecutor.shutdownNow();
            }
            if (!pendingExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                pendingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            newMessageExecutor.shutdownNow();
            pendingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
