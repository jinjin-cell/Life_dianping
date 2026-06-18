package com.lifedp.controller;


import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lifedp.dto.Result;
import com.lifedp.entity.Shop;
import com.lifedp.service.IShopService;
import com.lifedp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/shop")
public class ShopController {

    @Resource
    public IShopService shopService;

    /**
     * 根据id查询商铺信息（带缓存）
     * @param id 商铺id
     * @return 商铺详情数据
     */
    @GetMapping("/{id}")
    public Result queryShopById(@PathVariable("id") Long id) {
        return Result.ok(shopService.queryById(id));
    }

    /**
     * 新增商铺信息（带缓存失效）
     * @param shop 商铺数据
     * @return 商铺id
     */
    @PostMapping
    public Result saveShop(@RequestBody Shop shop) {
        shopService.saveWithCache(shop);
        return Result.ok(shop.getId());
    }

    /**
     * 更新商铺信息（带缓存失效）
     * @param shop 商铺数据
     * @return 无
     */
    @PutMapping
    public Result updateShop(@RequestBody Shop shop) {
        shopService.updateWithCache(shop);
        return Result.ok();
    }

    /**
     * 根据商铺类型分页查询商铺信息（带缓存，仅缓存第1页）。
     * 支持按距离/人气/评分排序
     */
    @GetMapping("/of/type")
    public Result queryShopByType(
            @RequestParam("typeId") Integer typeId,
            @RequestParam(value = "current", defaultValue = "1") Integer current,
            @RequestParam(value = "sortBy", required = false) String sortBy,
            @RequestParam(value = "x", required = false) Double x,
            @RequestParam(value = "y", required = false) Double y
    ) {
        // 距离排序：走 GEO
        if (StrUtil.isBlank(sortBy) && x != null && y != null) {
            List<Shop> nearby = shopService.queryNearby(typeId, x, y, current, SystemConstants.DEFAULT_PAGE_SIZE);
            return Result.ok(nearby);
        }
        // 人气/评分排序：走 DB 排序（不使用缓存，排序组合太多）
        if (StrUtil.isNotBlank(sortBy)) {
            Page<Shop> page;
            if ("comments".equals(sortBy)) {
                page = shopService.query()
                        .eq("type_id", typeId)
                        .orderByDesc("comments")
                        .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            } else if ("score".equals(sortBy)) {
                page = shopService.query()
                        .eq("type_id", typeId)
                        .orderByDesc("score")
                        .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            } else {
                page = shopService.queryByType(typeId, current, SystemConstants.DEFAULT_PAGE_SIZE);
            }
            return Result.ok(page.getRecords());
        }
        // 默认分页（使用缓存）
        Page<Shop> page = shopService.queryByType(typeId, current, SystemConstants.DEFAULT_PAGE_SIZE);
        return Result.ok(page.getRecords());
    }

    /** 手动加载所有商铺坐标到 Redis GEO（管理用） */
    @PostMapping("/geo/load")
    public Result loadGeo() {
        shopService.warmupShopGeo();
        return Result.ok("GEO数据加载完成");
    }

    /**
     * 根据商铺名称关键字分页查询商铺信息（不走缓存，搜索场景）
     * @param name 商铺名称关键字
     * @param current 页码
     * @return 商铺列表
     */
    @GetMapping("/of/name")
    public Result queryShopByName(
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "current", defaultValue = "1") Integer current
    ) {
        Page<Shop> page = shopService.query()
                .like(StrUtil.isNotBlank(name), "name", name)
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        return Result.ok(page.getRecords());
    }
}
