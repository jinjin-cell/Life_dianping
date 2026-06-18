package com.lifedp.Interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.lifedp.dto.UserDTO;
import com.lifedp.utils.RedisHelper;
import com.lifedp.utils.UserHolder;
import com.lifedp.utils.UvTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.lifedp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.lifedp.utils.RedisConstants.LOGIN_USER_TTL;
import static com.lifedp.utils.RedisConstants.LOGIN_USER_TOKEN_LIST_KEY;

@Slf4j
@Component
public class RefreshTokenInterceptor implements HandlerInterceptor {

    /** ★ Token 剩余时间小于此阈值才刷新（秒），减少不必要写操作 */
    private static final long REFRESH_THRESHOLD = 1800L; // 30分钟

    @Resource
    private RedisHelper redisHelper;

    @Resource
    private UvTracker uvTracker;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) {
        // 1. 从请求头获取 Token
        String token = request.getHeader("authorization");
        if (StrUtil.isBlank(token)) {
            return true;
        }

        // 2. 从 Redis Hash 中获取用户信息（Redis异常时降级放行，避免阻塞所有请求）
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap;
        try {
            userMap = redisHelper.entriesHash(key);
        } catch (Exception e) {
            log.warn("RefreshToken Redis异常: {}", e.toString());
            return true;
        }
        if (userMap == null || userMap.isEmpty()) {
            return true;
        }

        // 3. 反序列化 → ThreadLocal
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        UserHolder.saveUser(userDTO);

        // 5.记录 UV（HyperLogLog 幂等去重，失败不影响业务）
        try {
            uvTracker.record(userDTO.getId());
        } catch (Exception ignored) {
        }

        // 6.仅在剩余时间不足 30 分钟时才刷新（避免每次请求都写 Redis）
        try {
            redisHelper.expireIfLessThan(key, REFRESH_THRESHOLD, LOGIN_USER_TTL, TimeUnit.SECONDS);
            // P0: 同步刷新 Token 反向索引 Set 的 TTL
            String tokenSetKey = LOGIN_USER_TOKEN_LIST_KEY + userDTO.getId();
            redisHelper.expireIfLessThan(tokenSetKey, REFRESH_THRESHOLD, LOGIN_USER_TTL, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }

        return true;
    }
}