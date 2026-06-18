package com.lifedp.service;

import com.lifedp.entity.SeckillVoucher;
import com.baomidou.mybatisplus.extension.service.IService;

import java.time.LocalDateTime;

/**
 * 秒杀券管理服务。
 * 职责: 秒杀券CRUD、Redis库存预热、库存查询、活动时间校验。
 * 秒杀下单逻辑不在此处，由 VoucherOrderService 负责。
 */
public interface ISeckillVoucherService extends IService<SeckillVoucher> {

    /**
     * 将秒杀券库存预热到Redis，TTL = 活动剩余时间 + 1小时缓冲。
     * 用于秒杀券创建时首次写入，允许覆盖已有 key。
     */
    void warmupStockToRedis(Long voucherId, int stock, LocalDateTime endTime);

    /**
     * 懒加载库存到 Redis（SETNX 语义，仅在 key 不存在时写入）。
     * 用于 Redis 库存 key 缺失时的兜底回灌 —— 防止用 DB 旧数据覆盖
     * 正在被 Lua 原子扣减的实时库存。
     * 内部通过 initial_stock - order_SET_size 推算真实库存，fallbackStock 仅在无法对账时使用。
     */
    void lazyWarmupStockToRedis(Long voucherId, int fallbackStock, LocalDateTime endTime);

    /**
     * 持久化初始库存到 Redis（无 TTL），作为懒加载对账的基准值。
     * 每次创建秒杀券时调用。
     */
    void saveInitialStock(Long voucherId, int stock);

    /**
     * 查询Redis中的实时库存
     */
    Integer queryRedisStock(Long voucherId);

    /**
     * 校验秒杀券是否在有效时间窗口内，校验通过返回券对象，不通过返回null
     */
    SeckillVoucher getValidSeckillVoucher(Long voucherId);
}
