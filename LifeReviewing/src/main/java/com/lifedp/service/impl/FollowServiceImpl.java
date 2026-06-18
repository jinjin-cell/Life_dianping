package com.lifedp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import com.lifedp.dto.Result;
import com.lifedp.dto.UserDTO;
import com.lifedp.entity.Follow;
import com.lifedp.entity.User;
import com.lifedp.mapper.FollowMapper;
import com.lifedp.service.IFollowService;
import com.lifedp.service.IUserInfoService;
import com.lifedp.service.IUserService;
import com.lifedp.utils.RedisHelper;
import com.lifedp.utils.UserHolder;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.lifedp.utils.RedisConstants.FOLLOWS_KEY;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private IUserService userService;

    @Resource
    private IUserInfoService userInfoService;

    @Resource
    private RedisHelper redisHelper;

    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        String key = FOLLOWS_KEY + userId;
        if (isFollow) {
            // 不能关注自己
            if (userId.equals(followUserId)) {
                return Result.fail("不能关注自己");
            }
            // 检查是否已关注
            Long count = query().eq("user_id", userId)
                    .eq("follow_user_id", followUserId).count();
            if (count > 0) {
                return Result.ok();
            }
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            follow.setCreateTime(LocalDateTime.now());
            save(follow);
            // 同步 Redis Set + 计数
            redisHelper.sadd(key, followUserId.toString());
            userInfoService.updateFolloweeCount(userId, 1);
            userInfoService.updateFansCount(followUserId, 1);
        } else {
            boolean removed = remove(new LambdaQueryWrapper<Follow>()
                    .eq(Follow::getUserId, userId)
                    .eq(Follow::getFollowUserId, followUserId));
            if (removed) {
                // 同步 Redis Set + 计数
                redisHelper.srem(key, followUserId.toString());
                userInfoService.updateFolloweeCount(userId, -1);
                userInfoService.updateFansCount(followUserId, -1);
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        Long userId = UserHolder.getUser().getId();
        Long count = query().eq("user_id", userId)
                .eq("follow_user_id", followUserId).count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key1 = FOLLOWS_KEY + userId;
        String key2 = FOLLOWS_KEY + id;
        // Redis Set 交集，O(N) 取小集合
        Set<String> intersect = redisHelper.sinter(key1, key2);
        if (CollectionUtil.isEmpty(intersect)) {
            // Redis 可能无数据（重启/未预热），回退 DB 查询
            List<Long> myFollows = query().eq("user_id", userId).list()
                    .stream().map(Follow::getFollowUserId).collect(Collectors.toList());
            List<Long> otherFollows = query().eq("user_id", id).list()
                    .stream().map(Follow::getFollowUserId).collect(Collectors.toList());
            if (CollectionUtil.isEmpty(myFollows) || CollectionUtil.isEmpty(otherFollows)) {
                return Result.ok(Collections.emptyList());
            }
            myFollows.retainAll(otherFollows);
            if (CollectionUtil.isEmpty(myFollows)) {
                return Result.ok(Collections.emptyList());
            }
            // 查用户信息，转为 DTO
            List<UserDTO> users = userService.listByIds(myFollows)
                    .stream()
                    .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                    .collect(Collectors.toList());
            return Result.ok(users);
        }
        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        // 查用户信息，转为 DTO
        List<UserDTO> users = userService.listByIds(ids)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public List<Follow> queryFans(Long userId) {
        return query().eq("follow_user_id", userId).list();
    }

    @Override
    public List<Follow> queryFollows(Long userId) {
        return query().eq("user_id", userId).list();
    }

    @Override
    public Result queryFansWithInfo(Long userId) {
        // 查询粉丝的 Follow 记录
        List<Follow> fans = query().eq("follow_user_id", userId).list();
        if (CollectionUtil.isEmpty(fans)) {
            return Result.ok(Collections.emptyList());
        }
        // 提取粉丝的用户 ID，查用户表获取头像昵称
        List<Long> fanIds = fans.stream()
                .map(Follow::getUserId)
                .collect(Collectors.toList());
        List<UserDTO> userDTOs = userService.listByIds(fanIds)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }

    @Override
    public Result queryFollowsWithInfo(Long userId) {
        // 查询关注的人的 Follow 记录
        List<Follow> follows = query().eq("user_id", userId).list();
        if (CollectionUtil.isEmpty(follows)) {
            return Result.ok(Collections.emptyList());
        }
        // 提取关注的用户 ID，查用户表获取头像昵称
        List<Long> followIds = follows.stream()
                .map(Follow::getFollowUserId)
                .collect(Collectors.toList());
        List<UserDTO> userDTOs = userService.listByIds(followIds)
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOs);
    }
}
