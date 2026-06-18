package com.lifedp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lifedp.dto.Result;
import com.lifedp.entity.Voucher;
import com.lifedp.mapper.VoucherMapper;
import com.lifedp.entity.SeckillVoucher;
import com.lifedp.service.ISeckillVoucherService;
import com.lifedp.service.IVoucherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class VoucherServiceImpl extends ServiceImpl<VoucherMapper, Voucher> implements IVoucherService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Override
    public Result queryVoucherOfShop(Long shopId) {
        List<Voucher> vouchers = getBaseMapper().queryVoucherOfShop(shopId);
        // 秒杀券用 Redis 实时库存覆盖 DB 快照库存，保证前端显示一致
        for (Voucher v : vouchers) {
            if (v.getType() != null && v.getType() != 0) {
                Integer redisStock = seckillVoucherService.queryRedisStock(v.getId());
                if (redisStock != null) {
                    v.setStock(redisStock);
                } else {
                    // Redis 库存 key 缺失（TTL 到期或重启），从 DB 回灌
                    // v.getStock() / v.getEndTime() 来自 LEFT JOIN tb_seckill_voucher
                    log.info("Redis库存缺失，从DB懒加载, voucherId={}, stock={}", v.getId(), v.getStock());
                    seckillVoucherService.lazyWarmupStockToRedis(
                            v.getId(), v.getStock(), v.getEndTime());
                }
            }
        }
        return Result.ok(vouchers);
    }

    @Override
    @Transactional
    public void addSeckillVoucher(Voucher voucher) {
        save(voucher);
        SeckillVoucher seckillVoucher = new SeckillVoucher();
        seckillVoucher.setVoucherId(voucher.getId());
        seckillVoucher.setStock(voucher.getStock());
        seckillVoucher.setBeginTime(voucher.getBeginTime());
        seckillVoucher.setEndTime(voucher.getEndTime());
        seckillVoucherService.save(seckillVoucher);

        seckillVoucherService.warmupStockToRedis(voucher.getId(),
                voucher.getStock(), voucher.getEndTime());

        // 持久化初始库存(无TTL)，作为懒加载对账基准
        seckillVoucherService.saveInitialStock(voucher.getId(), voucher.getStock());
    }
}
