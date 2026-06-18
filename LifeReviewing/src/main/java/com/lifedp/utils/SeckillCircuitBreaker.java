package com.lifedp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Redis 熔断器。
 * 状态机: CLOSED(正常) → 连续失败N次 → OPEN(熔断,直接拒绝) → 冷却T秒 → HALF_OPEN(试探) → 成功 → CLOSED / 失败 → OPEN。
 * 熔断期间所有Redis操作直接降级，保护系统不被慢故障拖垮。
 */
@Slf4j
@Component
public class SeckillCircuitBreaker {

    private static final int FAILURE_THRESHOLD = 10;
    private static final long OPEN_COOLDOWN_MS = 30_000;
    private static final int HALF_OPEN_MAX_REQUESTS = 3;

    private enum State { CLOSED, OPEN, HALF_OPEN }

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);
    private final AtomicInteger halfOpenCount = new AtomicInteger(0);

    /** 请求是否被允许通过 (CLOSED或HALF_OPEN允许，OPEN拒绝) */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() > OPEN_COOLDOWN_MS) {
                // CAS 保证只有一个线程完成 OPEN→HALF_OPEN 转换
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    halfOpenCount.set(0);
                    log.warn("Circuit breaker: OPEN → HALF_OPEN, probing Redis");
                    return true;
                }
                // CAS 失败，另一线程已转换，重入判断
                return allowRequest();
            }
            return false;
        }
        // HALF_OPEN: 限制探测请求数，防止大量请求涌入
        return halfOpenCount.incrementAndGet() <= HALF_OPEN_MAX_REQUESTS;
    }

    public void recordSuccess() {
        if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
            consecutiveFailures.set(0);
            log.info("Circuit breaker: HALF_OPEN → CLOSED, Redis recovered");
        } else {
            consecutiveFailures.set(0);
        }
    }

    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        State current = state.get();
        if (current == State.CLOSED && failures >= FAILURE_THRESHOLD) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                log.error("Circuit breaker: CLOSED → OPEN after {} consecutive failures", failures);
            }
        } else if (current == State.HALF_OPEN) {
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                openedAt.set(System.currentTimeMillis());
                log.error("Circuit breaker: HALF_OPEN → OPEN, probe failed");
            }
        }
    }

    public boolean isOpen() {
        return state.get() == State.OPEN;
    }

    public String getState() {
        return state.get().name();
    }
}
