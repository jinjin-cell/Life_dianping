package com.lifedp.service.impl;

import com.lifedp.dto.Result;
import com.lifedp.dto.UserDTO;
import com.lifedp.entity.SeckillVoucher;
import com.lifedp.entity.VoucherOrder;
import com.lifedp.mapper.VoucherOrderMapper;
import com.lifedp.service.ISeckillVoucherService;
import com.lifedp.service.IVoucherOrderService;
import com.lifedp.utils.RedisHelper;
import com.lifedp.utils.SeckillCircuitBreaker;
import com.lifedp.utils.SeckillConstants;
import com.lifedp.utils.SeckillMetrics;
import com.lifedp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 秒杀下单核心服务。
 * 流程: 身份校验 → 熔断检查 → 时间校验 → 预生成orderId → Lua原子扣库存 → 投递Stream(失败回滚) → 返回orderId。
 * 熔断器: Redis连续失败10次→OPEN 30s→HALF_OPEN探活→恢复或再次熔断。
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private RedisHelper redisHelper;

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private SeckillMetrics metrics;

    @Resource
    private SeckillCircuitBreaker circuitBreaker;

    @Override
    public Result seckillVoucher(Long voucherId) {
        long startTime = System.currentTimeMillis();
        metrics.recordRequest();

        // 1. 身份校验 (LoginInterceptor已保证登录态，此处为defense-in-depth)
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();

        // 2. Redis熔断检查: 熔断打开时直接拒绝，保护DB不被拖垮
        if (!circuitBreaker.allowRequest()) {
            metrics.recordCircuitOpen();
            return Result.fail("系统繁忙，请稍后再试");
        }

        // 3. 秒杀时间窗口校验 (委托给 SeckillVoucherService)
        SeckillVoucher seckillVoucher;
        try {
            seckillVoucher = seckillVoucherService.getValidSeckillVoucher(voucherId);
        } catch (Exception e) {
            log.error("秒杀券校验异常, voucherId={}", voucherId, e);
            metrics.recordError();
            return Result.fail("系统异常，请稍后重试");
        }
        if (seckillVoucher == null) {
            return Result.fail("秒杀活动不存在或不在有效时间内");
        }

        // 4. 预生成订单ID (在扣库存之前)
        long orderId;
        try {
            orderId = generateOrderId();
        } catch (Exception e) {
            log.error("订单ID生成异常, voucherId={}, userId={}", voucherId, userId, e);
            metrics.recordError();
            return Result.fail("系统异常，请稍后重试");
        }

        // 4.5. Redis 库存兜底: key 不存在时从 DB 懒加载 (SETNX 防并发覆盖)
        String stockKey = SeckillConstants.SECKILL_STOCK_KEY + voucherId;
        if (!redisHelper.exists(stockKey)) {
            log.info("秒杀库存key不存在，从DB懒加载, voucherId={}, stock={}", voucherId, seckillVoucher.getStock());
            seckillVoucherService.lazyWarmupStockToRedis(voucherId,
                    seckillVoucher.getStock(), seckillVoucher.getEndTime());
        }

        // 5. Lua原子扣库存 + 防重复购买
        // 返回值: 1=扣减成功, 0=库存不足, -1=重复购买
        try {
            Long result = redisHelper.executeSeckillLua(voucherId, userId);
            circuitBreaker.recordSuccess();
            if (result == -1) {
                metrics.recordDuplicate();
                return Result.fail("不能重复购买");
            }
            if (result == 0) {
                metrics.recordStockOut();
                return Result.fail("库存不足");
            }
        } catch (Exception e) {
            log.error("秒杀Lua执行异常, voucherId={}, userId={}", voucherId, userId, e);
            circuitBreaker.recordFailure();
            metrics.recordError();
            return Result.fail("系统异常，请稍后重试");
        }

        // 6. 投递Redis Stream异步落库，失败时回滚库存
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);
        order.setStatus(1);
        order.setCreateTime(LocalDateTime.now());

        Map<String, String> fields = new HashMap<>();
        fields.put("orderId", String.valueOf(orderId));
        fields.put("voucherId", String.valueOf(voucherId));
        fields.put("userId", String.valueOf(userId));
        try {
            redisHelper.addToStream(SeckillConstants.SECKILL_STREAM_KEY, fields);
            metrics.recordStreamPublish();
        } catch (Exception e) {
            log.error("Redis Stream写入异常, 回滚库存 orderId={}", orderId, e);
            circuitBreaker.recordFailure();
            metrics.recordError();
            try {
                redisHelper.rollbackSeckillLua(voucherId, userId);
                log.info("库存回滚成功, voucherId={}, userId={}", voucherId, userId);
            } catch (Exception rollbackEx) {
                log.error("库存回滚失败! 需人工补偿, voucherId={}, userId={}, orderId={}",
                        voucherId, userId, orderId, rollbackEx);
                metrics.recordRollbackFailed();
            }
            return Result.fail("系统繁忙，请稍后再试");
        }

        metrics.recordSuccess();
        long responseTime = System.currentTimeMillis() - startTime;
        metrics.recordResponseTime(voucherId, responseTime);

        return Result.ok(orderId);
    }

    /**
     * 生成订单ID: 时间戳(毫秒)*1000 + Redis自增序号%1000。
     * 每秒支持1000个订单不冲突，且ID不可预测枚举。
     */
    private long generateOrderId() {
        long timestamp = System.currentTimeMillis();
        long seq = redisHelper.increment(SeckillConstants.SECKILL_ORDER_ID_KEY, 1) % 1000;
        return timestamp * 1000 + seq;
    }
}
