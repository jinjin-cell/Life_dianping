package com.lifedp.service;

import com.lifedp.dto.Result;
import com.lifedp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IFollowService extends IService<Follow> {

    /**
     * 关注或取关
     * @param followUserId 要关注/取关的用户id
     * @param isFollow true=关注, false=取关
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断当前用户是否已关注目标用户
     * @param followUserId 目标用户id
     */
    Result isFollow(Long followUserId);

    /**
     * 查询当前用户与目标用户的共同关注
     * @param id 目标用户id
     */
    Result followCommons(Long id);

    /**
     * 获取用户的粉丝列表
     */
    List<Follow> queryFans(Long userId);

    /**
     * 获取用户关注的人列表
     */
    List<Follow> queryFollows(Long userId);

    /**
     * 获取粉丝列表（含用户名和头像）
     */
    Result queryFansWithInfo(Long userId);

    /**
     * 获取关注列表（含用户名和头像）
     */
    Result queryFollowsWithInfo(Long userId);
}
