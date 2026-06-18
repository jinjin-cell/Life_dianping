package com.lifedp;

import com.lifedp.dto.Result;
import com.lifedp.dto.UserDTO;
import com.lifedp.entity.SeckillVoucher;
import com.lifedp.entity.VoucherOrder;
import com.lifedp.mapper.VoucherOrderMapper;
import com.lifedp.service.ISeckillVoucherService;
import com.lifedp.service.impl.VoucherOrderServiceImpl;
import com.lifedp.utils.RedisHelper;
import com.lifedp.utils.SeckillCircuitBreaker;
import com.lifedp.utils.SeckillConstants;
import com.lifedp.utils.SeckillMetrics;
import com.lifedp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Slf4j
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("秒杀系统高并发测试")
class SeckillConcurrencyTest {

    @Mock
    private RedisHelper redisHelper;

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    @Mock
    private VoucherOrderMapper voucherOrderMapper;

    @Mock
    private SeckillMetrics metrics;

    @Mock
    private SeckillCircuitBreaker circuitBreaker;

    @InjectMocks
    private VoucherOrderServiceImpl voucherOrderService;

    private static final Long VOUCHER_ID = 1L;
    private static final int STOCK = 100;

    private volatile InMemoryLuaSimulator luaSimulator;

    private static class InMemoryLuaSimulator {
        private final ConcurrentHashMap<String, Integer> stockMap = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, Set<Long>> userSet = new ConcurrentHashMap<>();

        void init(Long voucherId, int stock) {
            stockMap.put("seckill:stock:" + voucherId, stock);
            userSet.put("seckill:order:" + voucherId, ConcurrentHashMap.newKeySet());
        }

        Long execute(Long voucherId, Long userId) {
            String orderKey = "seckill:order:" + voucherId;
            Set<Long> users = userSet.computeIfAbsent(orderKey, k -> ConcurrentHashMap.newKeySet());
            if (!users.add(userId)) {
                return -1L;
            }
            String stockKey = "seckill:stock:" + voucherId;
            Integer current = stockMap.get(stockKey);
            if (current == null || current <= 0) {
                users.remove(userId);
                return 0L;
            }
            stockMap.merge(stockKey, 1, (old, dec) -> old - 1);
            int after = stockMap.get(stockKey);
            if (after < 0) {
                stockMap.put(stockKey, 0);
                users.remove(userId);
                return 0L;
            }
            return 1L;
        }

        void rollback(Long voucherId, Long userId) {
            String orderKey = "seckill:order:" + voucherId;
            Set<Long> users = userSet.get(orderKey);
            if (users != null) {
                users.remove(userId);
            }
            String stockKey = "seckill:stock:" + voucherId;
            stockMap.merge(stockKey, 1, Integer::sum);
        }

        int getStock(Long voucherId) {
            Integer s = stockMap.get("seckill:stock:" + voucherId);
            return s == null ? 0 : s;
        }

        int getUserCount(Long voucherId) {
            Set<Long> users = userSet.get("seckill:order:" + voucherId);
            return users == null ? 0 : users.size();
        }
    }

    @BeforeEach
    void setUp() {
        luaSimulator = new InMemoryLuaSimulator();
        luaSimulator.init(VOUCHER_ID, STOCK);

        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(VOUCHER_ID);
        seckillVoucher.setStock(STOCK);
        seckillVoucher.setBeginTime(LocalDateTime.now().minusHours(1));
        seckillVoucher.setEndTime(LocalDateTime.now().plusHours(1));
        when(seckillVoucherService.getValidSeckillVoucher(VOUCHER_ID)).thenReturn(seckillVoucher);

        lenient().when(circuitBreaker.allowRequest()).thenReturn(true);

        lenient().doAnswer(inv -> luaSimulator.execute(
                inv.getArgument(0, Long.class),
                inv.getArgument(1, Long.class)
        )).when(redisHelper).executeSeckillLua(anyLong(), anyLong());

        lenient().doAnswer(inv -> {
            luaSimulator.rollback(inv.getArgument(0, Long.class),
                    inv.getArgument(1, Long.class));
            return 1L;
        }).when(redisHelper).rollbackSeckillLua(anyLong(), anyLong());

        AtomicLong orderIdCounter = new AtomicLong(10000);
        lenient().doAnswer(inv -> orderIdCounter.incrementAndGet())
                .when(redisHelper).increment(anyString(), anyLong());

        lenient().doAnswer(inv -> null)
                .when(redisHelper).addToStream(anyString(), anyMap());

        doNothing().when(metrics).recordRequest();
        doNothing().when(metrics).recordSuccess();
        doNothing().when(metrics).recordStockOut();
        doNothing().when(metrics).recordDuplicate();
        doNothing().when(metrics).recordRateLimit();
        doNothing().when(metrics).recordError();
        doNothing().when(metrics).recordStreamPublish();
        doNothing().when(metrics).recordResponseTime(anyLong(), anyLong());
    }

    @AfterEach
    void tearDown() {
        UserHolder.removeUser();
    }

    private void mockUser(Long userId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setNickName("User-" + userId);
        UserHolder.saveUser(user);
    }

    // ==================== 场景1：防超卖 ====================

    @Test
    @DisplayName("防超卖: 200个不同用户同时抢100个库存 → 恰好100人成功,0超卖")
    void antiOverselling_200users_100stock_exactly100success() throws Exception {
        int userCount = 200;
        int stock = STOCK;
        CountDownLatch latch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (long i = 1; i <= userCount; i++) {
            final long uid = i;
            new Thread(() -> {
                try {
                    mockUser(uid);
                    Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (result.getSuccess()) successCount.incrementAndGet();
                } finally {
                    UserHolder.removeUser();
                    latch.countDown();
                }
            }, "T-" + uid).start();
        }

        boolean done = latch.await(30, TimeUnit.SECONDS);
        assertTrue(done, "所有线程应在30秒内完成");

        log.info("200用户抢100库存 → 成功: {}, 模拟器库存剩余: {}, 购买人数: {}",
                successCount.get(),
                luaSimulator.getStock(VOUCHER_ID), luaSimulator.getUserCount(VOUCHER_ID));

        assertEquals(stock, successCount.get(), "恰好100人成功");
        assertEquals(0, luaSimulator.getStock(VOUCHER_ID), "库存必须完全耗尽");
        verify(metrics, atLeast(stock)).recordSuccess();
    }

    @Test
    @DisplayName("防超卖: 1000个用户抢50个库存 → 恰好50人成功")
    void antiOverselling_1000users_50stock_exactly50success() throws Exception {
        int stock = 50;
        luaSimulator.init(VOUCHER_ID, stock);
        SeckillVoucher sv = new SeckillVoucher();
        sv.setVoucherId(VOUCHER_ID);
        sv.setStock(stock);
        sv.setBeginTime(LocalDateTime.now().minusHours(1));
        sv.setEndTime(LocalDateTime.now().plusHours(1));
        when(seckillVoucherService.getValidSeckillVoucher(VOUCHER_ID)).thenReturn(sv);

        int userCount = 1000;
        CountDownLatch latch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (long i = 1; i <= userCount; i++) {
            final long uid = i;
            new Thread(() -> {
                try {
                    mockUser(uid);
                    Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (result.getSuccess()) successCount.incrementAndGet();
                } finally {
                    UserHolder.removeUser();
                    latch.countDown();
                }
            }).start();
        }

        latch.await(30, TimeUnit.SECONDS);

        assertEquals(stock, successCount.get());
        assertEquals(0, luaSimulator.getStock(VOUCHER_ID));
    }

    // ==================== 场景2：防重复购买 ====================

    @Test
    @DisplayName("防重复: 同一用户并发发100次 → 恰好成功1次")
    void antiDuplicate_sameUser_100requests_only1success() throws Exception {
        Long userId = 999L;
        int concurrentRequests = 100;
        CountDownLatch latch = new CountDownLatch(concurrentRequests);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger duplicateCount = new AtomicInteger(0);

        for (int i = 0; i < concurrentRequests; i++) {
            new Thread(() -> {
                try {
                    mockUser(userId);
                    Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (result.getSuccess()) successCount.incrementAndGet();
                    else if ("不能重复购买".equals(result.getErrorMsg())) duplicateCount.incrementAndGet();
                } finally {
                    UserHolder.removeUser();
                    latch.countDown();
                }
            }).start();
        }

        latch.await(30, TimeUnit.SECONDS);

        assertEquals(1, successCount.get(), "同一用户只能成功1次");
        assertEquals(99, duplicateCount.get());
        assertEquals(STOCK - 1, luaSimulator.getStock(VOUCHER_ID));
    }

    @Test
    @DisplayName("防重复: 50个用户各发50次 → 每个用户最多成功1次,总成功≤50")
    void antiDuplicate_50users_50triesEach() throws Exception {
        int userCount = 50;
        int triesPerUser = 50;
        CountDownLatch latch = new CountDownLatch(userCount * triesPerUser);
        ConcurrentHashMap<Long, Integer> successPerUser = new ConcurrentHashMap<>();

        for (long uid = 1; uid <= userCount; uid++) {
            final long userId = uid;
            for (int t = 0; t < triesPerUser; t++) {
                new Thread(() -> {
                    try {
                        mockUser(userId);
                        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                        if (result.getSuccess()) successPerUser.merge(userId, 1, Integer::sum);
                    } finally {
                        UserHolder.removeUser();
                        latch.countDown();
                    }
                }).start();
            }
        }

        latch.await(60, TimeUnit.SECONDS);

        int totalSuccess = successPerUser.values().stream().mapToInt(Integer::intValue).sum();

        successPerUser.forEach((uid, count) ->
                assertEquals(1, count, "用户" + uid + "成功次数应为1,实际为" + count));
        assertTrue(totalSuccess <= STOCK);
    }

    // ==================== 场景3：时间校验 ====================

    @Test
    @DisplayName("秒杀券不存在时拒绝")
    void reject_whenVoucherNotFound() throws Exception {
        when(seckillVoucherService.getValidSeckillVoucher(VOUCHER_ID)).thenReturn(null);
        mockUser(1L);
        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("秒杀活动不存在或不在有效时间内", result.getErrorMsg());
    }

    // ==================== 场景4：混合全流程 ====================

    @Test
    @DisplayName("混合场景: 200正常用户 + 100重复用户抢100库存")
    void mixedScenario_normalAndDuplicate() throws Exception {
        int normalUsers = 200;
        int duplicateUsers = 100;

        lenient().doAnswer(inv -> {
            Long uid = inv.getArgument(1, Long.class);
            if (uid > normalUsers) return -1L;
            return luaSimulator.execute(inv.getArgument(0, Long.class), uid);
        }).when(redisHelper).executeSeckillLua(anyLong(), anyLong());

        int totalUsers = normalUsers + duplicateUsers;
        CountDownLatch latch = new CountDownLatch(totalUsers);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger duplicate = new AtomicInteger(0);

        for (long i = 1; i <= totalUsers; i++) {
            final long uid = i;
            new Thread(() -> {
                try {
                    mockUser(uid);
                    Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (result.getSuccess()) success.incrementAndGet();
                    else if ("不能重复购买".equals(result.getErrorMsg())) duplicate.incrementAndGet();
                } finally {
                    UserHolder.removeUser();
                    latch.countDown();
                }
            }).start();
        }

        latch.await(30, TimeUnit.SECONDS);

        log.info("混合场景 → 成功: {}, 重复: {}", success.get(), duplicate.get());
        assertEquals(100, success.get(), "200正常用户中恰好100抢到");
        assertEquals(100, duplicate.get(), "100应重复拦截");
    }

    // ==================== 场景5：边界条件 ====================

    @Test
    @DisplayName("库存为1时的极端并发: 1000人抢1个 → 恰好1人成功")
    void edgeCase_singleStock_1000users() throws Exception {
        int stock = 1;
        luaSimulator.init(VOUCHER_ID, stock);
        SeckillVoucher sv = new SeckillVoucher();
        sv.setVoucherId(VOUCHER_ID);
        sv.setStock(stock);
        sv.setBeginTime(LocalDateTime.now().minusHours(1));
        sv.setEndTime(LocalDateTime.now().plusHours(1));
        when(seckillVoucherService.getValidSeckillVoucher(VOUCHER_ID)).thenReturn(sv);

        int userCount = 1000;
        CountDownLatch latch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (long i = 1; i <= userCount; i++) {
            final long uid = i;
            new Thread(() -> {
                try {
                    mockUser(uid);
                    Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (result.getSuccess()) successCount.incrementAndGet();
                } finally {
                    UserHolder.removeUser();
                    latch.countDown();
                }
            }).start();
        }

        latch.await(30, TimeUnit.SECONDS);

        assertEquals(1, successCount.get(), "极端场景下也恰好1人成功");
    }

    @Test
    @DisplayName("Redis执行异常时返回系统异常提示")
    void handleRedisExceptionGracefully() throws Exception {
        when(redisHelper.executeSeckillLua(anyLong(), anyLong()))
                .thenThrow(new RuntimeException("Redis connection timeout"));

        mockUser(1L);
        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("系统异常，请稍后重试", result.getErrorMsg());
        verify(metrics).recordError();
    }

    @Test
    @DisplayName("Stream写入失败时回滚库存并提示重试")
    void rollbackStock_whenStreamFails() throws Exception {
        doThrow(new RuntimeException("Stream unavailable"))
                .when(redisHelper).addToStream(anyString(), anyMap());

        mockUser(1L);
        Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);

        assertFalse(result.getSuccess());
        assertEquals("系统繁忙，请稍后再试", result.getErrorMsg());
        verify(redisHelper).rollbackSeckillLua(eq(VOUCHER_ID), eq(1L));
        assertEquals(STOCK, luaSimulator.getStock(VOUCHER_ID), "库存应回滚恢复");
    }

    // ==================== 场景6：压力测试 ====================

    @Test
    @DisplayName("压力: 5000用户并发抢500库存 → 验证无超卖且库存精确")
    void stressTest_5000users_500stock() throws Exception {
        int stock = 500;
        int userCount = 5000;
        luaSimulator.init(VOUCHER_ID, stock);

        SeckillVoucher sv = new SeckillVoucher();
        sv.setVoucherId(VOUCHER_ID);
        sv.setStock(stock);
        sv.setBeginTime(LocalDateTime.now().minusHours(1));
        sv.setEndTime(LocalDateTime.now().plusHours(1));
        when(seckillVoucherService.getValidSeckillVoucher(VOUCHER_ID)).thenReturn(sv);

        CountDownLatch latch = new CountDownLatch(userCount);
        AtomicInteger successCount = new AtomicInteger(0);

        long start = System.currentTimeMillis();
        for (long i = 1; i <= userCount; i++) {
            final long uid = i;
            new Thread(() -> {
                try {
                    mockUser(uid);
                    Result result = voucherOrderService.seckillVoucher(VOUCHER_ID);
                    if (result.getSuccess()) successCount.incrementAndGet();
                } finally {
                    UserHolder.removeUser();
                    latch.countDown();
                }
            }).start();
        }

        latch.await(60, TimeUnit.SECONDS);
        long duration = System.currentTimeMillis() - start;

        log.info("压力测试 5000用户抢500库存 → 成功: {}, 耗时: {}ms",
                successCount.get(), duration);

        assertEquals(stock, successCount.get(), "5000并发中恰好500人成功");
        assertEquals(0, luaSimulator.getStock(VOUCHER_ID));
        assertTrue(duration < 30000, "5000并发应在30s内完成");
    }
}
