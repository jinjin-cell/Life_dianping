package com.lifedp.service.impl;

import com.lifedp.entity.SeckillVoucher;
import com.lifedp.mapper.SeckillVoucherMapper;
import com.lifedp.service.ISeckillVoucherService;
import com.lifedp.utils.RedisHelper;
import com.lifedp.utils.SeckillConstants;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀券管理服务实现。
 *
 * 核心职责:
 *  - 库存预热: 秒杀券创建时将库存写入Redis，TTL=活动剩余时间+1h缓冲
 *  - 库存查询: 从Redis读取实时剩余库存
 *  - 活动校验: 校验秒杀券是否在有效时间窗口内
 */
@Slf4j
@Service
public class SeckillVoucherServiceImpl extends ServiceImpl<SeckillVoucherMapper, SeckillVoucher> implements ISeckillVoucherService {

    @Resource
    private RedisHelper redisHelper;

    /**
     * 秒杀券库存预热到Redis（创建时专用，允许覆盖已有 key）。
     */
    @Override
    public void warmupStockToRedis(Long voucherId, int stock, LocalDateTime endTime) {
        String stockKey = SeckillConstants.SECKILL_STOCK_KEY + voucherId;
        long ttl = ChronoUnit.SECONDS.between(LocalDateTime.now(), endTime) + 3600;
        redisHelper.setString(stockKey, String.valueOf(stock), ttl, TimeUnit.SECONDS);
        log.info("秒杀券库存预热完成, voucherId={}, stock={}, ttl={}s", voucherId, stock, ttl);
    }

    /**
     * 懒加载库存到Redis（SETNX 语义）。
     * 核心思路: 用 initial_stock - order_SET_size 反推真实剩余库存，避免 DB 异步延迟导致回灌旧值。
     * 对账流程:
     *   1. 读 seckill:stock:init:{id} (无TTL) → 获取初始库存
     *   2. 读 seckill:order:{id} SCARD → 获取已购买人数(Redis 强一致)
     *   3. resolved = max(0, initial - bought)
     *   4. 与 DB fallback 比较，取更保守的值 → SETNX 写入
     * 不可对账时(Redis全量重启)降级使用 fallbackStock(DB值)。
     */
    @Override
    public void lazyWarmupStockToRedis(Long voucherId, int fallbackStock, LocalDateTime endTime) {
        String stockKey = SeckillConstants.SECKILL_STOCK_KEY + voucherId;
        int resolvedStock = resolveStockFromRedis(voucherId, fallbackStock);

        long ttl = ChronoUnit.SECONDS.between(LocalDateTime.now(), endTime) + 3600;
        if (ttl < 60) {
            ttl = 60;
        }
        Boolean success = redisHelper.setIfAbsent(stockKey, String.valueOf(resolvedStock), ttl, TimeUnit.SECONDS);
        if (Boolean.TRUE.equals(success)) {
            log.info("秒杀券库存懒加载完成, voucherId={}, resolvedStock={} (fallback={}, ttl={}s)",
                    voucherId, resolvedStock, fallbackStock, ttl);
        } else {
            log.info("秒杀券库存懒加载跳过(key已存在), voucherId={}", voucherId);
        }
    }

    /**
     * 从 Redis 对账推算真实库存: initial_stock - SCARD(order_set)。
     * 不可对账时返回 fallbackStock。
     */
    private int resolveStockFromRedis(Long voucherId, int fallbackStock) {
        try {
            String initialKey = SeckillConstants.SECKILL_STOCK_INIT_KEY + voucherId;
            String initialStr = redisHelper.getString(initialKey);
            if (initialStr == null) {
                return fallbackStock;
            }
            int initial = Integer.parseInt(initialStr);

            String orderKey = SeckillConstants.SECKILL_ORDER_KEY + voucherId;
            Long bought = redisHelper.scard(orderKey);
            long boughtCount = bought != null ? bought : 0;

            int reconciled = (int) Math.max(0, initial - boughtCount);
            // 取更保守的值: 对账结果 vs DB快照
            return Math.min(reconciled, fallbackStock);
        } catch (Exception e) {
            log.warn("库存对账异常, voucherId={}, 降级使用fallbackStock={}", voucherId, fallbackStock, e);
            return fallbackStock;
        }
    }

    /**
     * 持久化初始库存(无TTL)，供懒加载时对账使用。
     */
    @Override
    public void saveInitialStock(Long voucherId, int stock) {
        String initialKey = SeckillConstants.SECKILL_STOCK_INIT_KEY + voucherId;
        redisHelper.setStringPersist(initialKey, String.valueOf(stock));
        log.info("初始库存持久化完成, voucherId={}, initialStock={}", voucherId, stock);
    }

    /**
     * 从Redis查询秒杀券库存。
     * @param voucherId 秒杀券ID
     * @return 库存数量或null
     * @return
     */
    @Override
    public Integer queryRedisStock(Long voucherId) {
        String stockKey = SeckillConstants.SECKILL_STOCK_KEY + voucherId;
        String stockStr = redisHelper.getString(stockKey);
        if (stockStr == null) {
            return null;
        }
        try {
            return Integer.parseInt(stockStr);
        } catch (NumberFormatException e) {
            log.warn("Redis库存数据异常, voucherId={}, value={}", voucherId, stockStr);
            return null;
        }
    }

    private static final String SECKILL_VOUCHER_CACHE_KEY = "cache:seckill:voucher:";
    private static final long SECKILL_VOUCHER_CACHE_TTL_SECONDS = 30;

    /**
     * 校验秒杀券有效窗口 (先查Redis缓存，未命中再查DB)。
     */
    @Override
    public SeckillVoucher getValidSeckillVoucher(Long voucherId) {
        String cacheKey = SECKILL_VOUCHER_CACHE_KEY + voucherId;
        try {
            SeckillVoucher cached = redisHelper.getObject(cacheKey, SeckillVoucher.class);
            if (cached != null) {
                return isValidTimeWindow(cached) ? cached : null;
            }
        } catch (Exception e) {
            log.warn("秒杀券缓存查询异常, voucherId={}", voucherId, e);
        }

        SeckillVoucher seckillVoucher = getById(voucherId);
        if (seckillVoucher == null) {
            return null;
        }
        if (!isValidTimeWindow(seckillVoucher)) {
            return null;
        }

        try {
            long ttl = Math.max(SECKILL_VOUCHER_CACHE_TTL_SECONDS,
                    Duration.between(LocalDateTime.now(), seckillVoucher.getEndTime()).getSeconds());
            redisHelper.setObject(cacheKey, seckillVoucher, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("秒杀券缓存写入异常, voucherId={}", voucherId, e);
        }
        return seckillVoucher;
    }

    private boolean isValidTimeWindow(SeckillVoucher sv) {
        LocalDateTime now = LocalDateTime.now();
        return !now.isBefore(sv.getBeginTime()) && !now.isAfter(sv.getEndTime());
    }
}
