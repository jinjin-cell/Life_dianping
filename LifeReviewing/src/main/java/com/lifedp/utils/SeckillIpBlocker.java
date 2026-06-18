package com.lifedp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * IP级防刷控制器。
 * 同一IP在窗口内连续触发限流超过阈值 → 加入黑名单 → 冷却期内所有请求直接拒绝。
 * 防止恶意用户通过切换账号绕过用户级限流。
 */
@Slf4j
@Component
public class SeckillIpBlocker {

    private static final int IP_BLOCK_THRESHOLD = 50;
    private static final long IP_BLOCK_WINDOW_SECONDS = 10;
    private static final long IP_BLOCK_COOLDOWN_SECONDS = 120;

    private final ConcurrentHashMap<String, IpEntry> ipMap = new ConcurrentHashMap<>();

    private static class IpEntry {
        volatile long windowStart;
        volatile int count;
        volatile long blockedUntil;
    }

    public boolean isBlocked(String ip) {
        IpEntry entry = ipMap.get(ip);
        if (entry == null) {
            return false;
        }
        if (entry.blockedUntil > 0) {
            if (System.currentTimeMillis() < entry.blockedUntil) {
                return true;
            }
            entry.blockedUntil = 0;
            entry.count = 0;
        }
        return false;
    }

    public void recordRequest(String ip) {
        IpEntry entry = ipMap.computeIfAbsent(ip, k -> {
            IpEntry e = new IpEntry();
            e.windowStart = System.currentTimeMillis();
            return e;
        });

        long now = System.currentTimeMillis();
        if (now - entry.windowStart > IP_BLOCK_WINDOW_SECONDS * 1000) {
            entry.windowStart = now;
            entry.count = 1;
            return;
        }

        int count = ++entry.count;
        if (count >= IP_BLOCK_THRESHOLD) {
            entry.blockedUntil = now + IP_BLOCK_COOLDOWN_SECONDS * 1000;
            log.warn("IP {} blocked for {} seconds after {} requests in {}s",
                    maskIp(ip), IP_BLOCK_COOLDOWN_SECONDS, count, IP_BLOCK_WINDOW_SECONDS);
        }
    }

    public void cleanExpired() {
        long now = System.currentTimeMillis();
        ipMap.entrySet().removeIf(e -> {
            IpEntry entry = e.getValue();
            return entry.blockedUntil > 0 && now > entry.blockedUntil;
        });
    }

    private String maskIp(String ip) {
        int lastDot = ip.lastIndexOf('.');
        return lastDot > 0 ? ip.substring(0, lastDot) + ".*" : ip;
    }
}
