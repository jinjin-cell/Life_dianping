package com.lifedp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lifedp.entity.Shop;
import com.lifedp.mapper.ShopMapper;
import com.lifedp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lifedp.utils.CacheClient;
import com.lifedp.utils.RedisHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.lifedp.utils.RedisConstants.*;

@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private RedisHelper redisHelper;

    @Resource
    private CacheClient cacheClient;

    // 三级缓存查询: 本地 → Redis逻辑过期 → DB，过期时异步重建
    @Override
    public Shop queryById(Long id) {
        return cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY, LOCK_SHOP_KEY, id,
                Shop.class, this::getById,
                CACHE_SHOP_TTL, TimeUnit.MINUTES
        );
    }

    /**
     * 更新商铺并失效缓存：先写DB再删缓存（Cache-Aside模式）。
     * 类型变更时需同时失效新旧类型的列表缓存。
     */
    @Override
    @Transactional
    public Shop updateWithCache(Shop shop) {
        Shop old = getById(shop.getId());
        updateById(shop);
        try {
            cacheClient.invalidateAll(CACHE_SHOP_KEY + shop.getId());
            Long oldTypeId = old != null ? old.getTypeId() : null;
            Long newTypeId = shop.getTypeId();
            if (!Objects.equals(oldTypeId, newTypeId)) {
                if (oldTypeId != null) {
                    cacheClient.invalidateAll(CACHE_SHOP_KEY + "type:" + oldTypeId);
                }
                if (newTypeId != null) {
                    cacheClient.invalidateAll(CACHE_SHOP_KEY + "type:" + newTypeId);
                }
            }
        } catch (Exception e) {
            log.warn("Redis删除商铺缓存异常: id={}", shop.getId(), e);
        }
        return shop;
    }

    @Override
    @Transactional
    public Shop saveWithCache(Shop shop) {
        save(shop);
        try {
            if (shop.getTypeId() != null) {
                cacheClient.invalidateAll(CACHE_SHOP_KEY + "type:" + shop.getTypeId());
            }
        } catch (Exception e) {
            log.warn("Redis删除商铺类型缓存异常: typeId={}", shop.getTypeId(), e);
        }
        return shop;
    }

    /**
     * 仅缓存第1页（高频访问），非第1页直接查DB。
     */
    @Override
    public Page<Shop> queryByType(Integer typeId, Integer current, Integer pageSize) {
        String key = CACHE_SHOP_KEY + "type:" + typeId;
        if (current != null && current == 1) {
            try {
                String cached = redisHelper.getString(key);
                if (cached != null) {
                    TypeCacheData cacheData = JSONUtil.toBean(cached, TypeCacheData.class);
                    Page<Shop> page = new Page<>(1, pageSize);
                    page.setRecords(cacheData.records != null ? cacheData.records : Collections.emptyList());
                    page.setTotal(cacheData.total);
                    return page;
                }
            } catch (Exception e) {
                log.warn("Redis查询商铺类型缓存异常，降级到数据库: typeId={}", typeId, e);
            }
        }

        Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current != null ? current : 1, pageSize));
        if (page.getRecords() == null) {
            page.setRecords(Collections.emptyList());
        }
        if (current != null && current == 1) {
            try {
                List<Shop> records = page.getRecords();
                if (records == null) {
                    records = Collections.emptyList();
                }
                TypeCacheData cacheData = new TypeCacheData();
                cacheData.total = page.getTotal();
                cacheData.records = records;
                redisHelper.setString(key, JSONUtil.toJsonStr(cacheData),
                        CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("Redis写入商铺类型缓存异常: typeId={}", typeId, e);
            }
        }
        return page;
    }

    @Override
    public void warmupCache(Long id) {
        Shop shop = getById(id);
        if (shop != null) {
            cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + id, shop,
                    CACHE_SHOP_TTL, TimeUnit.MINUTES);
        }
    }

    @Override
    public void warmupHotShops() {
        try {
            List<Shop> hotShops = query()
                    .orderByDesc("score")
                    .last("LIMIT 10")
                    .list();
            for (Shop shop : hotShops) {
                cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + shop.getId(),
                        shop, CACHE_SHOP_TTL, TimeUnit.MINUTES);
            }
            log.info("热点商铺缓存预热完成, 共{}条", hotShops.size());
        } catch (Exception e) {
            log.warn("热点商铺缓存预热异常", e);
        }
    }

    @Override
    public boolean invalidateCache(Long id) {
        try {
            cacheClient.invalidateAll(CACHE_SHOP_KEY + id);
            return true;
        } catch (Exception e) {
            log.warn("商铺缓存失效异常: id={}", id, e);
            return false;
        }
    }

    @Override
    public void warmupShopGeo() {
        try {
            List<Shop> shops = list();
            for (Shop shop : shops) {
                if (shop.getX() != null && shop.getY() != null) {
                    String geoKey = SHOP_GEO_KEY + shop.getTypeId();
                    redisHelper.geoAdd(geoKey, shop.getX(), shop.getY(), shop.getId().toString());
                }
            }
            log.info("商铺GEO坐标预热完成, 共{}条", shops.size());
        } catch (Exception e) {
            log.warn("商铺GEO坐标预热异常", e);
        }
    }

    @Override
    public List<Shop> queryNearby(Integer typeId, Double x, Double y, int page, int pageSize) {
        String geoKey = SHOP_GEO_KEY + (typeId != null ? typeId : "0");
        long totalLimit = (long) page * pageSize + pageSize;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results =
                redisHelper.geoSearch(geoKey, x, y, 50, totalLimit);
        if (results == null || results.getContent().isEmpty()) {
            return Collections.emptyList();
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        int start = (page - 1) * pageSize;
        int end = Math.min(start + pageSize, content.size());
        if (start >= content.size()) {
            return Collections.emptyList();
        }
        Map<Long, Double> distanceMap = new LinkedHashMap<>();
        List<Long> shopIds = new ArrayList<>();
        for (int i = start; i < end; i++) {
            Long shopId = Long.valueOf(content.get(i).getContent().getName());
            double distanceMeters = content.get(i).getDistance().getValue();
            distanceMap.put(shopId, distanceMeters);
            shopIds.add(shopId);
        }
        if (shopIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<Shop> shops = listByIds(shopIds);
        Map<Long, Shop> shopMap = new LinkedHashMap<>();
        for (Shop s : shops) {
            shopMap.put(s.getId(), s);
        }
        List<Shop> ordered = new ArrayList<>();
        for (Long id : shopIds) {
            Shop s = shopMap.get(id);
            if (s != null) {
                s.setDistance(distanceMap.get(id));
                ordered.add(s);
            }
        }
        return ordered;
    }

    public static class TypeCacheData {
        public long total;
        public List<Shop> records;
    }
}
