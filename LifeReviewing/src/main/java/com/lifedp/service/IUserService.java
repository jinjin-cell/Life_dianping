package com.lifedp.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.lifedp.dto.LoginFormDTO;
import com.lifedp.dto.RegisterFormDTO;
import com.lifedp.dto.Result;
import com.lifedp.entity.User;

import jakarta.servlet.http.HttpServletRequest;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    /**
     * 手机号验证码登录或密码登录
     */
    Result login(LoginFormDTO loginForm);

    Result sendCode(String phone);

    /** 手动注册：手机号 + 密码 */
    Result register(RegisterFormDTO registerForm);

    Result logout(HttpServletRequest request);

    /** 忘记密码：发送重置验证码 */
    Result sendResetCode(String phone);

    /** 忘记密码：验证验证码并重置密码 */
    Result resetPassword(String phone, String code, String newPassword);

    Result sign();

    Result signCount();
}
