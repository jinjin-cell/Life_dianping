package com.lifedp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀限流器，基于Redis秒级滑动窗口。
 * 计数key: seckill:rate:{userId}:{epochSecond}，使用Lua原子INCR+EXPIRE避免竞态。
 */
@Slf4j
@Component
public class SeckillRateLimiter {

    @Resource
    private RedisHelper redisHelper;

    // 秒级滑动窗口: user:{秒级时间戳} 计数，超过阈值则拒绝
    public boolean isAllowed(Long userId) {
        long epochSecond = System.currentTimeMillis() / 1000;
        String key = SeckillConstants.SECKILL_RATE_LIMIT_KEY + userId + ":" + epochSecond;
        Long count = redisHelper.incrementWithExpire(key, 1,
                SeckillConstants.SECKILL_RATE_LIMIT_WINDOW, TimeUnit.SECONDS);
        return count != null && count <= SeckillConstants.SECKILL_RATE_LIMIT_MAX;
    }

    public boolean isGlobalAllowed() {
        long epochSecond = System.currentTimeMillis() / 1000;
        String key = SeckillConstants.SECKILL_GLOBAL_RATE_KEY + ":" + epochSecond;
        Long count = redisHelper.incrementWithExpire(key, 1,
                SeckillConstants.SECKILL_RATE_LIMIT_WINDOW, TimeUnit.SECONDS);
        return count != null && count <= SeckillConstants.SECKILL_GLOBAL_RATE_MAX;
    }
}
