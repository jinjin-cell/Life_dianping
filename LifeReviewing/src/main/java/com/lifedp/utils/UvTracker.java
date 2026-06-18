package com.lifedp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import static com.lifedp.utils.RedisConstants.UV_KEY;

/**
 * UV 统计组件，基于 Redis HyperLogLog 实现每日/每月/总计 UV。
 * PFADD 天然幂等去重，同一用户多次访问只计 1 次。
 */
@Slf4j
@Component
public class UvTracker {

    @Resource
    private RedisHelper redisHelper;

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 记录一次 UV（每个 authenticated 请求调用）
     * 写入日 key、月 key、总 key，HyperLogLog 自动去重
     */
    public void record(Long userId) {
        LocalDate today = LocalDate.now();
        String dayKey = UV_KEY + today.format(DAY_FMT);
        String monthKey = UV_KEY + "month:" + today.format(MONTH_FMT);
        String totalKey = UV_KEY + "total";
        try {
            redisHelper.pfadd(dayKey, userId.toString());
            redisHelper.pfadd(monthKey, userId.toString());
            redisHelper.pfadd(totalKey, userId.toString());
        } catch (Exception e) {
            log.warn("UV记录异常: userId={}", userId, e);
        }
    }

    /** 今日 UV */
    public long getTodayUv() {
        String dayKey = UV_KEY + LocalDate.now().format(DAY_FMT);
        try {
            Long result = redisHelper.pfcount(dayKey);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.warn("查询今日UV异常", e);
            return 0;
        }
    }

    /** 本月 UV（汇总当月所有日 key 后统计） */
    public long getMonthUv() {
        String monthPrefix = UV_KEY + LocalDate.now().format(MONTH_FMT);
        String mergedKey = monthPrefix + ":merged";
        try {
            Set<String> dayKeys = redisHelper.scanKeys(UV_KEY + "*");
            // 筛选当月日 key（不含 month 和 total 前缀）
            String monthPrefixFull = UV_KEY + LocalDate.now().format(MONTH_FMT);
            java.util.List<String> monthDayKeys = dayKeys.stream()
                    .filter(k -> k.startsWith(monthPrefixFull) && !k.contains("month") && !k.contains("total"))
                    .collect(java.util.stream.Collectors.toList());
            if (monthDayKeys.isEmpty()) {
                return 0;
            }
            redisHelper.delete(mergedKey);
            String[] sourceKeys = monthDayKeys.toArray(new String[0]);
            redisHelper.pfmerge(mergedKey, sourceKeys);
            Long result = redisHelper.pfcount(mergedKey);
            return result != null ? result : 0;
        } catch (Exception e) {
            log.warn("查询本月UV异常", e);
            return 0;
        }
    }

    /** 总计 UV */
    public long getTotalUv() {
        try {
            Long result = redisHelper.pfcount(UV_KEY + "total");
            return result != null ? result : 0;
        } catch (Exception e) {
            log.warn("查询总计UV异常", e);
            return 0;
        }
    }
}
