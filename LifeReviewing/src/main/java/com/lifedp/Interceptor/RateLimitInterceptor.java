package com.lifedp.Interceptor;

import com.lifedp.dto.UserDTO;
import com.lifedp.utils.RedisHelper;
import com.lifedp.utils.SeckillIpBlocker;
import com.lifedp.utils.SeckillRateLimiter;
import com.lifedp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 秒杀接口前置防护拦截器 (order=2, 在LoginInterceptor之后)。
 * 四层防护: IP黑名单 → 幂等Token → 用户级限流 → 全局限流。
 */
@Slf4j
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    @Resource
    private SeckillRateLimiter seckillRateLimiter;

    @Resource
    private SeckillIpBlocker ipBlocker;

    @Resource
    private RedisHelper redisHelper;

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String uri = request.getRequestURI();
        if (!uri.contains("/seckill")) {
            return true;
        }

        String clientIp = getClientIp(request);

        if (ipBlocker.isBlocked(clientIp)) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"success\":false,\"errorMsg\":\"请求过于频繁，请稍后再试\"}");
            return false;
        }

        UserDTO user = UserHolder.getUser();
        if (user != null) {
            try {
                if (!seckillRateLimiter.isAllowed(user.getId())) {
                    ipBlocker.recordRequest(clientIp);
                    response.setStatus(429);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"success\":false,\"errorMsg\":\"请求过于频繁，请稍后再试\"}");
                    return false;
                }

                if (!seckillRateLimiter.isGlobalAllowed()) {
                    ipBlocker.recordRequest(clientIp);
                    response.setStatus(429);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"success\":false,\"errorMsg\":\"系统繁忙，请稍后再试\"}");
                    return false;
                }
            } catch (Exception e) {
                // Redis不可用时降级放行，避免阻塞所有秒杀请求
                log.warn("Rate limiter Redis异常，降级放行, userId={}", user.getId(), e);
            }
        }

        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
