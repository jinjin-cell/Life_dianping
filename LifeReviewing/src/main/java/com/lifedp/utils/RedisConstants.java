package com.lifedp.utils;

public class RedisConstants {
    // ===== 原有 =====
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;          // 分钟
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;      // 秒

    // ===== 新增：安全防护 =====
    public static final String CODE_SEND_LIMIT_KEY = "code:send:limit:";  // 发送频率限制
    public static final Long CODE_SEND_LIMIT_TTL = 60L;                   // 秒，两次发送最小间隔
    public static final String CODE_ERROR_KEY = "code:error:";            // 验证码错误计数
    public static final Long CODE_ERROR_TTL = 10L;                        // 分钟，错误计数过期
    public static final int CODE_MAX_ERROR = 5;                           // 最大错误次数

    public static final String LOGIN_FAIL_KEY = "login:fail:";            // 登录失败计数
    public static final String LOGIN_LOCK_KEY = "login:lock:";            // 账户锁定标记（独立 key，避免类型混用）
    public static final Long LOGIN_FAIL_TTL = 15L;                        // 分钟，锁定时间
    public static final int LOGIN_MAX_FAIL = 5;                           // 最大失败次数

    // ===== 新增：忘记密码 =====
    public static final String RESET_CODE_KEY = "reset:code:";            // 重置密码验证码
    public static final Long RESET_CODE_TTL = 5L;                         // 分钟
    public static final String RESET_CODE_LIMIT_KEY = "reset:code:limit:";// 重置码发送限频
    public static final Long RESET_CODE_LIMIT_TTL = 60L;                  // 秒
    public static final String RESET_CODE_ERROR_KEY = "reset:code:error:";// 重置验证码错误计数
    public static final Long RESET_CODE_ERROR_TTL = 10L;                  // 分钟
    public static final int RESET_CODE_MAX_ERROR = 5;                     // 最大错误次数

    // ===== 新增：Token 反向索引 + IP 限流 + 每日上限 =====
    public static final String LOGIN_USER_TOKEN_LIST_KEY = "login:token:list:"; // Set：某用户的所有 Token
    public static final String CODE_SEND_IP_KEY = "code:ip:";             // IP 发送限频
    public static final Long CODE_SEND_IP_TTL = 60L;                      // 秒
    public static final int CODE_SEND_IP_MAX = 10;                        // 每分钟每 IP 最多发 10 次
    public static final String CODE_SEND_DAILY_KEY = "code:daily:";       // 每日发送计数
    public static final int CODE_SEND_DAILY_MAX = 10;                     // 每日上限
    public static final String REGISTER_IP_KEY = "register:ip:";          // 注册 IP 限频
    public static final Long REGISTER_IP_TTL = 3600L;                     // 秒
    public static final int REGISTER_IP_MAX = 5;                          // 每小时每 IP 最多注册 5 次

    // ===== 原有 =====
    public static final Long CACHE_NULL_TTL = 2L;
    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";
    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;
    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";
    public static final String FOLLOWS_KEY = "follows:";

    // ===== UV 统计 (HyperLogLog) =====
    public static final String UV_KEY = "uv:";
}