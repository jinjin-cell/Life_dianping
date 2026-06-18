package com.lifedp.controller;

import com.lifedp.dto.Result;
import com.lifedp.dto.UserDTO;
import com.lifedp.utils.RedisHelper;
import com.lifedp.utils.SeckillCircuitBreaker;
import com.lifedp.utils.SeckillMetrics;
import com.lifedp.utils.UvTracker;
import com.lifedp.utils.UserHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/monitor")
public class MonitorController {

    @Resource
    private SeckillMetrics seckillMetrics;

    @Resource
    private RedisHelper redisHelper;

    @Resource
    private SeckillCircuitBreaker circuitBreaker;

    @Resource
    private UvTracker uvTracker;

    @GetMapping("/metrics")
    public Result getMetrics() {
        return Result.ok(seckillMetrics.getMetrics());
    }

    @GetMapping("/health")
    public Result health() {
        Map<String, Object> health = new HashMap<>();
        health.put("status", "UP");
        health.put("timestamp", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(LocalDateTime.now()));

        try {
            Boolean redisOk = redisHelper.exists("metrics:seckill:start_time");
            health.put("redis", redisOk != null && redisOk ? "UP" : "DOWN");
        } catch (Exception e) {
            health.put("redis", "DOWN");
        }

        health.put("circuitBreaker", circuitBreaker.getState());
        health.put("rateLimiter", "UP");
        health.put("streamConsumer", "UP");
        health.put("cacheSystem", "UP");

        return Result.ok(health);
    }

    @PostMapping("/metrics/reset")
    public Result resetMetrics() {
        seckillMetrics.reset();
        return Result.ok("Metrics reset");
    }

    /** UV 统计（HyperLogLog 去重） */
    @GetMapping("/uv/stats")
    public Result uvStats() {
        Map<String, Object> uv = new HashMap<>();
        uv.put("todayUv", uvTracker.getTodayUv());
        uv.put("monthUv", uvTracker.getMonthUv());
        uv.put("totalUv", uvTracker.getTotalUv());
        return Result.ok(uv);
    }

    /** 手动记录一次 UV（测试用） */
    @PostMapping("/uv/record")
    public Result uvRecord() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("未登录");
        }
        uvTracker.record(user.getId());
        return Result.ok();
    }
}
