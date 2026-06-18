package com.lifedp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lifedp.dto.LoginFormDTO;
import com.lifedp.dto.RegisterFormDTO;
import com.lifedp.dto.Result;
import com.lifedp.dto.UserDTO;
import com.lifedp.entity.User;
import com.lifedp.mapper.UserMapper;
import com.lifedp.service.IUserService;
import com.lifedp.utils.PasswordEncoder;
import com.lifedp.utils.RedisHelper;
import com.lifedp.utils.RegexUtils;
import com.lifedp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.lifedp.utils.RedisConstants.*;
import static com.lifedp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    @Resource
    private RedisHelper redisHelper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    // ==================== 验证码发送 ====================
    @Override
    public Result sendCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // P2: IP 级别限流
        if (isIpSendLimited("login", phone)) {
            return Result.fail("操作过于频繁，请稍后再试");
        }
        // 手机号频率限制 — 用 SET NX 原子化，替代 exists + set
        String limitKey = CODE_SEND_LIMIT_KEY + phone;
        if (!redisHelper.setIfAbsent(limitKey, "1", CODE_SEND_LIMIT_TTL, TimeUnit.SECONDS)) {
            return Result.fail("验证码已发送，请60秒后再试");
        }

        // P5: 每日发送上限（放在 60s 检查之后，避免被拒请求消耗配额）
        if (isDailyLimitReached(phone)) {
            // 回滚 60s 限制，否则今日配额耗尽后用户连 60s 锁都被卡住
            redisHelper.delete(limitKey);
            return Result.fail("今日发送次数已达上限，请明天再试");
        }

        String code = RandomUtil.randomNumbers(6);
        redisHelper.setString(LOGIN_CODE_KEY + phone, code, LOGIN_CODE_TTL, TimeUnit.MINUTES);

        log.info("发送验证码成功，手机号：{}，验证码：{}", phone, code);
        return Result.ok();
    }

    // ==================== 注册 ====================
    @Override
    public Result register(RegisterFormDTO registerForm) {
        String phone = registerForm.getPhone();
        String password = registerForm.getPassword();

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        if (!RegexUtils.isPasswordValid(password)) {
            return Result.fail("密码格式错误！需为8~32位字母、数字或下划线");
        }

        // 检查手机号是否已注册
        User existingUser = query().eq("phone", phone).one();
        if (existingUser != null) {
            return Result.fail("该手机号已注册");
        }

        // P4: IP 级别注册限流
        if (isRegisterIpLimited()) {
            return Result.fail("注册过于频繁，请稍后再试");
        }

        // 创建用户
        User user = new User();
        user.setPhone(phone);
        user.setPassword(PasswordEncoder.encode(password));
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        save(user);

        // 清除注册前可能残留的失败计数和锁定（防止新用户刚注册就被锁）
        redisHelper.delete(LOGIN_FAIL_KEY + phone);
        redisHelper.delete(LOGIN_LOCK_KEY + phone);

        log.info("新用户注册成功，手机号：{}", phone);
        return Result.ok();
    }

    // ==================== 登录 ====================
    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();

        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // 检查账户是否被锁定
        String lockKey = LOGIN_LOCK_KEY + phone;
        if ("LOCKED".equals(redisHelper.getString(lockKey))) {
            return Result.fail("账户已被锁定，请15分钟后再试");
        }

        User user;

        // ========== 密码登录 ==========
        if (StrUtil.isNotBlank(loginForm.getPassword())) {
            user = query().eq("phone", phone).one();
            if (user == null || !PasswordEncoder.matches(user.getPassword(), loginForm.getPassword())) {
                recordLoginFail(phone);
                return Result.fail("手机号或密码错误");
            }
            // 旧 MD5 密码自动升级为 BCrypt
            if (PasswordEncoder.needsUpgrade(user.getPassword())) {
                user.setPassword(PasswordEncoder.encode(loginForm.getPassword()));
                updateById(user);
                log.info("密码已自动升级为 BCrypt，用户：{}", phone);
            }
        }
        // ========== 验证码登录 ==========
        else if (StrUtil.isNotBlank(loginForm.getCode())) {
            if (RegexUtils.isCodeInvalid(loginForm.getCode())) {
                return Result.fail("验证码格式错误！");
            }

            // 先检查用户是否存在，避免消耗验证码后才发现未注册
            user = query().eq("phone", phone).one();
            if (user == null) {
                return Result.fail("用户不存在，请先注册");
            }

            String errorKey = CODE_ERROR_KEY + phone;
            String errorCountStr = redisHelper.getString(errorKey);
            int errorCount = errorCountStr != null ? Integer.parseInt(errorCountStr) : 0;
            if (errorCount >= CODE_MAX_ERROR) {
                return Result.fail("验证码错误次数过多，请10分钟后再试");
            }

            // P3: 原子校验 + 删除验证码（Lua）
            Long checkResult = redisHelper.checkAndDelete(LOGIN_CODE_KEY + phone, loginForm.getCode());
            if (checkResult == null || checkResult != 1) {
                if (checkResult != null && checkResult == -1) {
                    redisHelper.incrementWithExpire(errorKey, 1, CODE_ERROR_TTL, TimeUnit.MINUTES);
                }
                return Result.fail("验证码错误");
            }
            // 验证码正确且已原子删除，仅清除错误计数
            redisHelper.delete(errorKey);
        } else {
            return Result.fail("请输入验证码或密码");
        }

        // 登录成功：清除所有失败/锁定记录
        redisHelper.delete(LOGIN_FAIL_KEY + phone);
        redisHelper.delete(LOGIN_LOCK_KEY + phone);
        redisHelper.delete(CODE_ERROR_KEY + phone);

        // 检查是否已有有效 Token，有则复用
        String tokenSetKey = LOGIN_USER_TOKEN_LIST_KEY + user.getId();
        Set<String> existingTokens = redisHelper.smembers(tokenSetKey);
        String token = null;
        if (existingTokens != null && !existingTokens.isEmpty()) {
            for (String t : existingTokens) {
                if (Boolean.TRUE.equals(redisHelper.exists(LOGIN_USER_KEY + t))) {
                    token = t;
                    break;
                } else {
                    // 清理无效 token 引用
                    redisHelper.srem(tokenSetKey, t);
                }
            }
        }

        // 无有效 Token 则生成新 Token
        if (token == null) {
            token = UUID.randomUUID().toString().replace("-", "");
        }

        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        String tokenKey = LOGIN_USER_KEY + token;

        Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, false, true);
        redisHelper.putAllHash(tokenKey, userMap);
        redisHelper.expire(tokenKey, LOGIN_USER_TTL, TimeUnit.SECONDS);

        // P0: 维护 Token 反向索引
        redisHelper.sadd(tokenSetKey, token);
        redisHelper.expire(tokenSetKey, LOGIN_USER_TTL, TimeUnit.SECONDS);

        return Result.ok(token);
    }

    // ==================== 登出 ====================
    @Override
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("authorization");
        if (StrUtil.isNotBlank(token)) {
            redisHelper.delete(LOGIN_USER_KEY + token);
            // P0: 从 Token 反向索引中移除
            UserDTO user = UserHolder.getUser();
            if (user != null) {
                redisHelper.srem(LOGIN_USER_TOKEN_LIST_KEY + user.getId(), token);
            }
        }
        UserHolder.removeUser();
        return Result.ok();
    }

    // ==================== 忘记密码 ====================
    @Override
    public Result sendResetCode(String phone) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }

        // P2: IP 级别限流
        if (isIpSendLimited("reset", phone)) {
            return Result.fail("操作过于频繁，请稍后再试");
        }

        // 手机号频率限制
        String limitKey = RESET_CODE_LIMIT_KEY + phone;
        if (!redisHelper.setIfAbsent(limitKey, "1", RESET_CODE_LIMIT_TTL, TimeUnit.SECONDS)) {
            return Result.fail("验证码已发送，请60秒后再试");
        }

        // P1: 用户枚举防护 — 无论用户是否存在都返回"验证码已发送"
        User user = query().eq("phone", phone).one();
        if (user == null) {
            log.info("未注册手机号请求重置密码：{}", phone);
            return Result.ok(); // 静默：不泄露未注册信息
        }

        String code = RandomUtil.randomNumbers(6);
        redisHelper.setString(RESET_CODE_KEY + phone, code, RESET_CODE_TTL, TimeUnit.MINUTES);

        log.info("发送重置密码验证码，手机号：{}，验证码：{}", phone, code);
        return Result.ok();
    }

    @Override
    public Result resetPassword(String phone, String code, String newPassword) {
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误！");
        }
        if (RegexUtils.isCodeInvalid(code)) {
            return Result.fail("验证码格式错误！");
        }
        if (!RegexUtils.isPasswordValid(newPassword)) {
            return Result.fail("密码格式错误！需为8~32位字母、数字或下划线");
        }

        // 验证码错误计数检查
        String errorKey = RESET_CODE_ERROR_KEY + phone;
        String errorCountStr = redisHelper.getString(errorKey);
        int errorCount = errorCountStr != null ? Integer.parseInt(errorCountStr) : 0;
        if (errorCount >= RESET_CODE_MAX_ERROR) {
            return Result.fail("验证码错误次数过多，请10分钟后再试");
        }

        // P3: 原子校验验证码
        Long checkResult = redisHelper.checkAndDelete(RESET_CODE_KEY + phone, code);
        if (checkResult == null || checkResult != 1) {
            if (checkResult != null && checkResult == -1) {
                redisHelper.incrementWithExpire(errorKey, 1, RESET_CODE_ERROR_TTL, TimeUnit.MINUTES);
            }
            return Result.fail("验证码错误");
        }
        redisHelper.delete(errorKey);

        // 查询用户
        User user = query().eq("phone", phone).one();
        if (user == null) {
            return Result.fail("用户不存在");
        }

        // 更新密码
        user.setPassword(PasswordEncoder.encode(newPassword));
        updateById(user);

        // P0: 踢掉该用户所有旧 Token
        String tokenSetKey = LOGIN_USER_TOKEN_LIST_KEY + user.getId();
        Set<String> tokens = redisHelper.smembers(tokenSetKey);
        if (tokens != null && !tokens.isEmpty()) {
            for (String t : tokens) {
                redisHelper.delete(LOGIN_USER_KEY + t);
            }
        }
        redisHelper.delete(tokenSetKey);

        // 清除登录失败计数和锁定，防止重置密码后仍无法登录
        redisHelper.delete(LOGIN_FAIL_KEY + phone);
        redisHelper.delete(LOGIN_LOCK_KEY + phone);

        log.info("密码重置成功，手机号：{}", phone);
        return Result.ok();
    }

    // ==================== 登录失败记录 ====================
    private void recordLoginFail(String phone) {
        String countKey = LOGIN_FAIL_KEY + phone;
        Long failCount = redisHelper.incrementWithExpire(countKey, 1, LOGIN_FAIL_TTL, TimeUnit.MINUTES);
        if (failCount != null && failCount >= LOGIN_MAX_FAIL) {
            String lockKey = LOGIN_LOCK_KEY + phone;
            redisHelper.setString(lockKey, "LOCKED", LOGIN_FAIL_TTL, TimeUnit.MINUTES);
            log.warn("账户已被锁定：{}，失败次数：{}", phone, failCount);
        }
    }

    // ==================== 限流辅助 ====================

    /** P2: IP 级别验证码发送限流 */
    private boolean isIpSendLimited(String action, String phone) {
        String ip = getClientIp();
        if (ip == null) return false;
        String key = CODE_SEND_IP_KEY + action + ":" + ip;
        // 用 SET NX 先设置计数器（首次为 1），非首次则 INCR
        if (redisHelper.setIfAbsent(key, "1", CODE_SEND_IP_TTL, TimeUnit.SECONDS)) {
            return false;
        }
        Long count = redisHelper.increment(key, 1);
        return count != null && count > CODE_SEND_IP_MAX;
    }

    /** P4: IP 级别注册限流 */
    private boolean isRegisterIpLimited() {
        String ip = getClientIp();
        if (ip == null) return false;
        String key = REGISTER_IP_KEY + ip;
        if (redisHelper.setIfAbsent(key, "1", REGISTER_IP_TTL, TimeUnit.SECONDS)) {
            return false;
        }
        Long count = redisHelper.increment(key, 1);
        return count != null && count > REGISTER_IP_MAX;
    }

    /** P5: 每日短信发送上限 */
    private boolean isDailyLimitReached(String phone) {
        String key = CODE_SEND_DAILY_KEY + phone;
        Long count = redisHelper.increment(key, 1);
        if (count != null && count == 1) {
            // 首次发送：设置过期时间为次日零点
            redisHelper.expire(key, RedisHelper.secondsUntilMidnight(), TimeUnit.SECONDS);
        }
        return count != null && count > CODE_SEND_DAILY_MAX;
    }

    /** 获取客户端真实 IP（支持反向代理） */
    private String getClientIp() {
        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) return null;
            HttpServletRequest request = attrs.getRequest();
            String ip = request.getHeader("X-Forwarded-For");
            if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getHeader("X-Real-IP");
            }
            if (StrUtil.isBlank(ip) || "unknown".equalsIgnoreCase(ip)) {
                ip = request.getRemoteAddr();
            }
            if (ip != null && ip.contains(",")) {
                ip = ip.split(",")[0].trim();
            }
            return ip;
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 签到 ====================
    @Override
    public Result sign() {
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        String key = USER_SIGN_KEY + userId + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int dayOfMonth = now.getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(key, dayOfMonth - 1, true);
        return Result.ok();
    }

    /**
     * 获取当前用户签到次数
     */
    @Override
    public Result signCount() {
        Long userId = UserHolder.getUser().getId();
        LocalDate now = LocalDate.now();
        String key = USER_SIGN_KEY + userId + ":" + now.format(DateTimeFormatter.ofPattern("yyyyMM"));
        int dayOfMonth = now.getDayOfMonth();

        List<Long> result = stringRedisTemplate.opsForValue().bitField(
                key,
                BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.unsigned(dayOfMonth)).valueAt(0)
        );
        if (result == null || result.isEmpty()) {
            return Result.ok(0);
        }
        long word = result.get(0) == null ? 0 : result.get(0);
        int count = 0;
        // 从今天(高位bit)倒推连续签到天数，遇到未签到即停止
        for (int i = dayOfMonth - 1; i >= 0; i--) {
            if ((word >> i & 1) == 1) {
                count++;
            } else {
                break;
            }
        }
        return Result.ok(count);
    }

}
