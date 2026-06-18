package com.lifedp;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lifedp.entity.Shop;
import com.lifedp.mapper.ShopMapper;
import com.lifedp.service.impl.ShopServiceImpl;
import com.lifedp.utils.RedisHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.lifedp.utils.RedisConstants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ShopService 缓存功能单元测试")
class ShopServiceCacheTest {

    @Mock
    private RedisHelper redisHelper;

    @Mock
    private ShopMapper shopMapper;

    @InjectMocks
    private ShopServiceImpl shopService;

    private Shop sampleShop;

    @BeforeEach
    void setUp() {
        sampleShop = new Shop();
        sampleShop.setId(1L);
        sampleShop.setName("测试商铺");
        sampleShop.setTypeId(2L);
        sampleShop.setArea("朝阳区");
        sampleShop.setAddress("测试地址");
        sampleShop.setAvgPrice(100L);
        sampleShop.setScore(45);
    }

    // ======================== queryById ========================

    @Test
    @DisplayName("queryById - 缓存命中时直接返回缓存数据，不查数据库")
    void shouldReturnCachedShopWhenCacheHit() {
        when(redisHelper.getObject(CACHE_SHOP_KEY + 1L, Shop.class))
                .thenReturn(sampleShop);

        Shop result = shopService.queryById(1L);

        assertNotNull(result);
        assertEquals("测试商铺", result.getName());
        verify(shopMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("queryById - 缓存未命中时查询数据库并写入缓存")
    void shouldQueryDbAndWriteCacheOnCacheMiss() {
        when(redisHelper.getObject(CACHE_SHOP_KEY + 1L, Shop.class))
                .thenReturn(null);
        when(shopMapper.selectById(1L)).thenReturn(sampleShop);

        Shop result = shopService.queryById(1L);

        assertNotNull(result);
        assertEquals("测试商铺", result.getName());
        verify(shopMapper).selectById(1L);
        verify(redisHelper).setObject(eq(CACHE_SHOP_KEY + 1L), eq(sampleShop),
                eq(CACHE_SHOP_TTL), any());
    }

    @Test
    @DisplayName("queryById - 商铺不存在时缓存null标记并返回null")
    void shouldCacheNullMarkerWhenShopNotExists() {
        when(redisHelper.getObject(CACHE_SHOP_KEY + 999L, Shop.class))
                .thenReturn(null);
        when(shopMapper.selectById(999L)).thenReturn(null);

        Shop result = shopService.queryById(999L);

        assertNull(result);
        ArgumentCaptor<Shop> captor = ArgumentCaptor.forClass(Shop.class);
        verify(redisHelper).setObject(eq(CACHE_SHOP_KEY + 999L), captor.capture(),
                eq(CACHE_NULL_TTL), any());
        assertEquals(-1L, captor.getValue().getId());
    }

    @Test
    @DisplayName("queryById - 缓存中已有null标记时直接返回null，不查数据库")
    void shouldReturnNullWhenNullMarkerCached() {
        Shop nullMarker = new Shop();
        nullMarker.setId(-1L);
        when(redisHelper.getObject(CACHE_SHOP_KEY + 999L, Shop.class))
                .thenReturn(nullMarker);

        Shop result = shopService.queryById(999L);

        assertNull(result);
        verify(shopMapper, never()).selectById(any());
    }

    @Test
    @DisplayName("queryById - Redis异常时降级到数据库查询并正常返回")
    void shouldDegradeToDbWhenRedisFails() {
        when(redisHelper.getObject(CACHE_SHOP_KEY + 1L, Shop.class))
                .thenThrow(new RuntimeException("Redis连接失败"));
        when(shopMapper.selectById(1L)).thenReturn(sampleShop);

        Shop result = shopService.queryById(1L);

        assertNotNull(result);
        assertEquals("测试商铺", result.getName());
        verify(shopMapper).selectById(1L);
    }

    @Test
    @DisplayName("queryById - Redis写入异常时不影响数据返回")
    void shouldStillReturnDataWhenRedisWriteFails() {
        when(redisHelper.getObject(CACHE_SHOP_KEY + 1L, Shop.class))
                .thenReturn(null);
        when(shopMapper.selectById(1L)).thenReturn(sampleShop);
        doThrow(new RuntimeException("Redis写入失败"))
                .when(redisHelper).setObject(anyString(), any(), anyLong(), any());

        Shop result = shopService.queryById(1L);

        assertNotNull(result);
        assertEquals("测试商铺", result.getName());
    }

    // ======================== updateWithCache ========================

    @Test
    @DisplayName("updateWithCache - 更新商铺后删除单个缓存，类型不变时不删除类型缓存")
    void shouldOnlyInvalidateSingleCacheWhenTypeUnchanged() {
        Shop old = new Shop();
        old.setId(1L);
        old.setTypeId(2L);
        when(shopMapper.selectById(1L)).thenReturn(old);
        when(shopMapper.updateById(sampleShop)).thenReturn(1);
        doNothing().when(redisHelper).delete(anyString());

        shopService.updateWithCache(sampleShop);

        verify(redisHelper).delete(CACHE_SHOP_KEY + 1L);
        verify(redisHelper, never()).delete(CACHE_SHOP_KEY + "type:" + 2L);
    }

    @Test
    @DisplayName("updateWithCache - 类型变更时同时删除新旧类型缓存")
    void shouldInvalidateBothOldAndNewTypeCacheWhenTypeChanged() {
        Shop old = new Shop();
        old.setId(1L);
        old.setTypeId(2L);
        sampleShop.setTypeId(3L);

        when(shopMapper.selectById(1L)).thenReturn(old);
        when(shopMapper.updateById(sampleShop)).thenReturn(1);
        doNothing().when(redisHelper).delete(anyString());

        shopService.updateWithCache(sampleShop);

        verify(redisHelper).delete(CACHE_SHOP_KEY + 1L);
        verify(redisHelper).delete(CACHE_SHOP_KEY + "type:" + 2L);
        verify(redisHelper).delete(CACHE_SHOP_KEY + "type:" + 3L);
    }

    @Test
    @DisplayName("updateWithCache - Redis删除缓存异常时不影响更新操作")
    void shouldNotFailWhenRedisDeleteThrowsException() {
        Shop old = new Shop();
        old.setId(1L);
        old.setTypeId(2L);
        sampleShop.setTypeId(3L);
        when(shopMapper.selectById(1L)).thenReturn(old);
        when(shopMapper.updateById(sampleShop)).thenReturn(1);
        doThrow(new RuntimeException("Redis删除失败"))
                .when(redisHelper).delete(anyString());

        Shop result = shopService.updateWithCache(sampleShop);

        assertNotNull(result);
        assertEquals("测试商铺", result.getName());
    }

    // ======================== saveWithCache ========================

    @Test
    @DisplayName("saveWithCache - 新增商铺后删除该类型的缓存")
    void shouldInvalidateTypeCacheOnSave() {
        when(shopMapper.insert(sampleShop)).thenReturn(1);
        doNothing().when(redisHelper).delete(anyString());

        shopService.saveWithCache(sampleShop);

        verify(redisHelper).delete(CACHE_SHOP_KEY + "type:" + 2L);
    }

    @Test
    @DisplayName("saveWithCache - Redis删除异常时不影响新增操作")
    void shouldNotFailWhenRedisDeleteThrowsExceptionOnSave() {
        when(shopMapper.insert(sampleShop)).thenReturn(1);
        doThrow(new RuntimeException("Redis删除失败"))
                .when(redisHelper).delete(anyString());

        Shop result = shopService.saveWithCache(sampleShop);

        assertNotNull(result);
        assertEquals("测试商铺", result.getName());
    }

    // ======================== queryByType ========================

    @Test
    @DisplayName("queryByType - 第1页缓存命中时返回正确的total和records")
    void shouldReturnCachedDataWithCorrectTotal() {
        List<Shop> cachedList = new ArrayList<>();
        cachedList.add(sampleShop);
        ShopServiceImpl.TypeCacheData cacheData = new ShopServiceImpl.TypeCacheData();
        cacheData.total = 50L;
        cacheData.records = cachedList;
        String json = JSONUtil.toJsonStr(cacheData);
        when(redisHelper.getString(CACHE_SHOP_KEY + "type:" + 2)).thenReturn(json);

        Page<Shop> result = shopService.queryByType(2, 1, 5);

        assertNotNull(result);
        assertEquals(1, result.getRecords().size());
        assertEquals(50L, result.getTotal());
        assertEquals("测试商铺", result.getRecords().get(0).getName());
        verify(shopMapper, never()).selectPage(any(), any());
    }

    @Test
    @DisplayName("queryByType - 缓存未命中时查询数据库并写入正确的total")
    void shouldQueryDbAndWriteCacheWithCorrectTotal() {
        Page<Shop> dbPage = new Page<>(1, 5);
        dbPage.setRecords(Collections.singletonList(sampleShop));
        dbPage.setTotal(50L);
        when(redisHelper.getString(CACHE_SHOP_KEY + "type:" + 2)).thenReturn(null);
        when(shopMapper.selectPage(any(), any())).thenReturn(dbPage);
        doNothing().when(redisHelper)
                .setString(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        shopService.queryByType(2, 1, 5);

        verify(shopMapper).selectPage(any(), any());
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(redisHelper).setString(eq(CACHE_SHOP_KEY + "type:" + 2), jsonCaptor.capture(),
                eq(CACHE_SHOP_TTL), any());
        ShopServiceImpl.TypeCacheData cached = JSONUtil.toBean(jsonCaptor.getValue(),
                ShopServiceImpl.TypeCacheData.class);
        assertEquals(50L, cached.total);
        assertEquals(1, cached.records.size());
    }

    @Test
    @DisplayName("queryByType - 非第1页时跳过缓存直接查询数据库")
    void shouldSkipCacheForNonFirstPage() {
        Page<Shop> dbPage = new Page<>(2, 5);
        dbPage.setRecords(new ArrayList<>());
        dbPage.setTotal(50L);
        when(shopMapper.selectPage(any(), any())).thenReturn(dbPage);

        Page<Shop> result = shopService.queryByType(2, 2, 5);

        verify(redisHelper, never()).getString(anyString());
        verify(shopMapper).selectPage(any(), any());
        assertNotNull(result);
    }

    @Test
    @DisplayName("queryByType - Redis异常时降级到数据库查询")
    void shouldDegradeToDbWhenRedisFailsOnTypeQuery() {
        Page<Shop> dbPage = new Page<>(1, 5);
        dbPage.setRecords(new ArrayList<>());
        dbPage.setTotal(50L);
        when(redisHelper.getString(CACHE_SHOP_KEY + "type:" + 2))
                .thenThrow(new RuntimeException("Redis连接失败"));
        when(shopMapper.selectPage(any(), any())).thenReturn(dbPage);

        Page<Shop> result = shopService.queryByType(2, 1, 5);

        assertNotNull(result);
        verify(shopMapper).selectPage(any(), any());
    }

    @Test
    @DisplayName("queryByType - DB返回records为null时写入空列表不报错")
    void shouldHandleNullRecordsGracefully() {
        Page<Shop> dbPage = new Page<>(1, 5);
        dbPage.setRecords(null);
        dbPage.setTotal(0L);
        when(redisHelper.getString(CACHE_SHOP_KEY + "type:" + 2)).thenReturn(null);
        when(shopMapper.selectPage(any(), any())).thenReturn(dbPage);
        doNothing().when(redisHelper)
                .setString(anyString(), anyString(), anyLong(), any(TimeUnit.class));

        Page<Shop> result = shopService.queryByType(2, 1, 5);

        assertNotNull(result);
        assertNotNull(result.getRecords());
        assertTrue(result.getRecords().isEmpty());
    }
}
