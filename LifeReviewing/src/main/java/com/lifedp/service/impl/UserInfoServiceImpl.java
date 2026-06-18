package com.lifedp.service.impl;

import com.lifedp.dto.Result;
import com.lifedp.dto.UserDTO;
import com.lifedp.entity.UserInfo;
import com.lifedp.mapper.UserInfoMapper;
import com.lifedp.service.IUserInfoService;
import com.lifedp.utils.UserHolder;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

    @Override
    @Transactional
    public Result saveUserInfo(UserInfo info) {
        UserDTO user = UserHolder.getUser();
        Long userId = user.getId();
        // 确保 userId 设置正确
        info.setUserId(userId);
        // 检查是否已存在记录
        UserInfo existing = getById(userId);
        if (existing != null) {
            info.setUpdateTime(LocalDateTime.now());
            updateById(info);
        } else {
            info.setCreateTime(LocalDateTime.now());
            info.setUpdateTime(LocalDateTime.now());
            info.setFans(0);
            info.setFollowee(0);
            info.setCredits(0);
            info.setLevel(false);
            save(info);
        }
        return Result.ok();
    }

    @Override
    public void updateFansCount(Long userId, int delta) {
        UserInfo info = getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
            info.setFans(Math.max(0, delta));
            info.setFollowee(0);
            info.setCredits(0);
            info.setLevel(false);
            info.setCreateTime(LocalDateTime.now());
            info.setUpdateTime(LocalDateTime.now());
            save(info);
        } else {
            int newFans = Math.max(0, info.getFans() + delta);
            update().set("fans", newFans).eq("user_id", userId).update();
        }
    }

    @Override
    public void updateFolloweeCount(Long userId, int delta) {
        UserInfo info = getById(userId);
        if (info == null) {
            info = new UserInfo();
            info.setUserId(userId);
            info.setFans(0);
            info.setFollowee(Math.max(0, delta));
            info.setCredits(0);
            info.setLevel(false);
            info.setCreateTime(LocalDateTime.now());
            info.setUpdateTime(LocalDateTime.now());
            save(info);
        } else {
            int newFollowee = Math.max(0, info.getFollowee() + delta);
            update().set("followee", newFollowee).eq("user_id", userId).update();
        }
    }
}
