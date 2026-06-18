package com.lifedp.config;

import com.lifedp.service.IShopService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;

/**
 * 启动时预热Top10评分最高的商铺到三级缓存，避免冷启动雪崩。
 * 预热失败不影响服务启动，业务请求会触发懒加载。
 */
@Slf4j
@Component
public class CacheWarmUpRunner implements CommandLineRunner {

    @Resource
    private IShopService shopService;

    @Override
    public void run(String... args) {
        log.info("开始热点商铺缓存预热...");
        try {
            shopService.warmupHotShops();
        } catch (Exception e) {
            log.warn("缓存预热失败，不影响服务启动", e);
        }
        log.info("开始商铺GEO坐标预热...");
        try {
            shopService.warmupShopGeo();
        } catch (Exception e) {
            log.warn("GEO坐标预热失败，不影响服务启动", e);
        }
    }
}
