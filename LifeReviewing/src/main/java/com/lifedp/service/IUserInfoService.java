package com.lifedp.service;

import com.lifedp.dto.Result;
import com.lifedp.entity.UserInfo;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IUserInfoService extends IService<UserInfo> {

    /**
     * 保存或更新用户详细信息
     */
    Result saveUserInfo(UserInfo info);

    /**
     * 更新粉丝数
     */
    void updateFansCount(Long userId, int delta);

    /**
     * 更新关注数
     */
    void updateFolloweeCount(Long userId, int delta);
}
