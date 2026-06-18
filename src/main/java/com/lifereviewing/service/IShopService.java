package com.lifereviewing.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lifereviewing.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IShopService extends IService<Shop> {

    Shop queryById(Long id);

    Shop updateWithCache(Shop shop);

    Shop saveWithCache(Shop shop);

    Page<Shop> queryByType(Integer typeId, Integer current, Integer pageSize);

    void warmupCache(Long id);

    void warmupHotShops();

    void warmupShopGeo();

    boolean invalidateCache(Long id);

    /**
     * 查询附近商铺（GEO 按距离排序）
     * @param typeId 商铺类型（null=全部）
     * @param x 当前经度
     * @param y 当前纬度
     * @param page 页码（1-based）
     * @param pageSize 每页条数
     * @return 带 distance 字段的商铺列表
     */
    List<Shop> queryNearby(Integer typeId, Double x, Double y, int page, int pageSize);
}
