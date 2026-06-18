-- KEYS[1]: seckill:stock:{voucherId}  库存计数
-- KEYS[2]: seckill:order:{voucherId}  已购买用户SET
-- ARGV[1]: userId
-- 返回: 1=扣减成功, 0=库存不足, -1=已购买(防重复)
-- 整个Lua在Redis单线程中原子执行，无需额外加锁

-- 第1步: 防重复购买 (SISMEMBER O(1))
if redis.call('SISMEMBER', KEYS[2], ARGV[1]) == 1 then
    return -1
end

-- 第2步: 检查库存
local stock = tonumber(redis.call('GET', KEYS[1]) or '0')
if stock <= 0 then
    return 0
end

-- 第3步: 原子扣库存 + 记录用户
redis.call('DECR', KEYS[1])
redis.call('SADD', KEYS[2], ARGV[1])

return 1
