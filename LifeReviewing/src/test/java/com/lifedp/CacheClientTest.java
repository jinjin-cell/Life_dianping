package com.lifedp;

import cn.hutool.json.JSONUtil;
import com.lifedp.entity.Shop;
import com.lifedp.service.IShopService;
import com.lifedp.utils.CacheClient;
import com.lifedp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.lifedp.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
public class CacheClientTest {

    @Resource
    private CacheClient cacheClient;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IShopService shopService;

    @Test
    void testCacheWarmupAndQuery() {
        shopService.warmupHotShops();
        String key = CACHE_SHOP_KEY + "1";
        String json = stringRedisTemplate.opsForValue().get(key);
        assertNotNull(json, "预热后缓存应存在");

        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        assertNotNull(redisData.getData());
        assertNotNull(redisData.getExpireTime());
        assertTrue(redisData.getExpireTime().isAfter(LocalDateTime.now()),
                "逻辑过期时间应在未来");
    }

    @Test
    void testQueryByIdReturnsData() {
        Shop shop = shopService.queryById(1L);
        assertNotNull(shop);
        assertNotNull(shop.getName());
    }

    @Test
    void testConcurrentQuery() throws InterruptedException {
        int threadCount = 50;
        CountDownLatch latch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    Shop shop = shopService.queryById(1L);
                    if (shop != null && shop.getId() != null) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await(30, TimeUnit.SECONDS);
        assertEquals(threadCount, successCount.get(),
                "所有并发请求都应成功返回数据");
    }

    @Test
    void testCacheInvalidation() {
        shopService.warmupCache(1L);
        String key = CACHE_SHOP_KEY + "1";
        assertNotNull(stringRedisTemplate.opsForValue().get(key));

        boolean result = shopService.invalidateCache(1L);
        assertTrue(result);
    }

    @Test
    void testQueryNonExistentShop() {
        Shop shop = shopService.queryById(999999L);
        assertNull(shop);
    }

    @Test
    void testQueryByType() {
        var page = shopService.queryByType(1, 1, 10);
        assertNotNull(page);
        assertTrue(page.getRecords() != null);
    }
}
