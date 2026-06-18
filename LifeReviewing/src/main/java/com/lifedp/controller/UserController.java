package com.lifedp.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.lifedp.dto.LoginFormDTO;
import com.lifedp.dto.RegisterFormDTO;
import com.lifedp.dto.Result;
import com.lifedp.dto.UserDTO;
import com.lifedp.entity.User;
import com.lifedp.entity.UserInfo;
import com.lifedp.service.IUserInfoService;
import com.lifedp.service.IUserService;
import com.lifedp.utils.RedisHelper;
import com.lifedp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;

import static com.lifedp.utils.RedisConstants.LOGIN_USER_KEY;

@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private RedisHelper redisHelper;

    /**
     * 发送手机验证码（Redis 存储）
     */
    @PostMapping("/code")
    public Result sendCode(@RequestParam("phone") String phone) {
        return userService.sendCode(phone);
    }

    /**
     * 手动注册（手机号 + 密码）
     */
    @PostMapping("/register")
    public Result register(@RequestBody RegisterFormDTO registerForm) {
        return userService.register(registerForm);
    }

    /**
     * 登录功能（支持密码或验证码，返回 Token）
     */
    @PostMapping("/login")
    public Result login(@RequestBody LoginFormDTO loginForm) {
        return userService.login(loginForm);
    }

    /**
     * 忘记密码：发送重置验证码
     */
    @PostMapping("/reset/code")
    public Result sendResetCode(@RequestParam("phone") String phone) {
        return userService.sendResetCode(phone);
    }

    /**
     * 忘记密码：验证验证码并重置密码
     */
    @PostMapping("/reset/password")
    public Result resetPassword(@RequestParam("phone") String phone,
                                @RequestParam("code") String code,
                                @RequestParam("password") String password) {
        return userService.resetPassword(phone, code, password);
    }

    /**
     * 登出功能
     */
    @PostMapping("/logout")
    public Result logout(HttpServletRequest request) {
        return userService.logout(request);
    }

    /**
     * 获取当前登录用户信息（由 RefreshTokenInterceptor 存入 UserHolder）
     */
    @GetMapping("/me")
    public Result me() {
        UserDTO user = UserHolder.getUser();
        return Result.ok(user);
    }

    /**
     * 更新当前用户基本信息（昵称、头像），同步更新 Redis Token 和前端本地缓存
     */
    @PutMapping("/me")
    public Result updateMe(@RequestBody User user, HttpServletRequest request) {
        UserDTO currentUser = UserHolder.getUser();
        user.setId(currentUser.getId());
        userService.updateById(user);
        // 刷新 UserHolder
        User updated = userService.getById(currentUser.getId());
        UserDTO updatedDTO = BeanUtil.copyProperties(updated, UserDTO.class);
        UserHolder.saveUser(updatedDTO);
        // 同步更新 Redis Token Hash，避免下次请求读到旧数据
        try {
            String token = request.getHeader("authorization");
            if (StrUtil.isNotBlank(token)) {
                String tokenKey = LOGIN_USER_KEY + token;
                if (user.getNickName() != null) {
                    redisHelper.putHash(tokenKey, "nickName", user.getNickName());
                }
                if (user.getIcon() != null) {
                    redisHelper.putHash(tokenKey, "icon", user.getIcon());
                }
            }
        } catch (Exception e) {
            log.warn("更新Token Hash失败", e);
        }
        return Result.ok();
    }

    /**
     * 查询用户详情（tb_user_info 表）
     */
    @GetMapping("/info/{id}")
    public Result info(@PathVariable("id") Long userId) {
        UserInfo info = userInfoService.getById(userId);
        if (info == null) {
            return Result.ok();
        }
        info.setCreateTime(null);
        info.setUpdateTime(null);
        return Result.ok(info);
    }

    /**
     * 根据 id 查询用户基本信息（tb_user 表）
     */
    @GetMapping("/{id}")
    public Result queryUserById(@PathVariable("id") Long userId) {
        User user = userService.getById(userId);
        if (user == null) {
            return Result.ok();
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        return Result.ok(userDTO);
    }

    /**
     * 保存用户详细信息（新增或更新）
     */
    @PutMapping("/info")
    public Result saveUserInfo(@RequestBody UserInfo info) {
        return userInfoService.saveUserInfo(info);
    }

    /**
     * 每日签到（Redis bitmap）
     */
    @PostMapping("/sign")
    public Result sign() {
        return userService.sign();
    }

    /**
     * 查询本月累计签到次数
     */
    @GetMapping("/sign/count")
    public Result signCount() {
        return userService.signCount();
    }
}