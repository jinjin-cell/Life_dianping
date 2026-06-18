package com.lifedp.service.impl;


import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HtmlUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lifedp.dto.Result;
import com.lifedp.dto.ScrollResult;
import com.lifedp.dto.UserDTO;
import com.lifedp.entity.Blog;
import com.lifedp.entity.Follow;
import com.lifedp.entity.User;
import com.lifedp.mapper.BlogMapper;
import com.lifedp.service.IBlogService;
import com.lifedp.service.IFollowService;
import com.lifedp.service.IUserService;
import com.lifedp.utils.RedisConstants;
import com.lifedp.utils.SystemConstants;
import com.lifedp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        // 填充用户信息
        User user = userService.getById(blog.getUserId());
        if (user != null) {
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
        }
        // 判断当前用户是否已点赞
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result likeBlog(Long id) {
        Long userId = UserHolder.getUser().getId();
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 判断是否已点赞
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if (score != null) {
            // 已点赞 → 取消点赞
            boolean removed = stringRedisTemplate.opsForZSet().remove(key, userId.toString()) > 0;
            if (removed) {
                update().setSql("liked = liked - 1").eq("id", id).update();
            }
        } else {
            // 未点赞 → 点赞
            boolean added = stringRedisTemplate.opsForZSet().add(key, userId.toString(),
                    System.currentTimeMillis());
            if (added) {
                update().setSql("liked = liked + 1").eq("id", id).update();
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        // 查询 top5 点赞用户
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (CollectionUtil.isEmpty(top5)) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIds = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        // 按 ZSET 顺序保持（DESC）
        List<User> users = userService.listByIds(userIds);
        // 重新按点赞时间排序
        List<UserDTO> userDTOs = users.stream().map(user -> {
            UserDTO dto = new UserDTO();
            dto.setId(user.getId());
            dto.setNickName(user.getNickName());
            dto.setIcon(user.getIcon());
            return dto;
        }).collect(Collectors.toList());
        return Result.ok(userDTOs);
    }

    @Override
    @Transactional
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        // 防 XSS: 标题和内容做 HTML 消毒，移除所有 HTML 标签和事件属性
        blog.setTitle(HtmlUtil.filter(blog.getTitle()));
        blog.setContent(HtmlUtil.filter(blog.getContent()));
        blog.setUserId(user.getId());
        blog.setCreateTime(LocalDateTime.now());
        blog.setUpdateTime(LocalDateTime.now());
        blog.setLiked(0);
        blog.setComments(0);
        boolean saved = save(blog);
        if (!saved) {
            return Result.fail("发布失败");
        }
        // 推送给粉丝的收件箱
        List<Follow> fans = followService.queryFans(user.getId());
        for (Follow fan : fans) {
            String feedKey = RedisConstants.FEED_KEY + fan.getUserId();
            stringRedisTemplate.opsForZSet().add(feedKey, blog.getId().toString(),
                    System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        Long userId = UserHolder.getUser().getId();
        String feedKey = RedisConstants.FEED_KEY + userId;
        // 滚动分页查询收件箱
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(feedKey, 0, max == null ? Long.MAX_VALUE : max,
                        offset == null ? 0 : offset, 3);
        if (CollectionUtil.isEmpty(typedTuples)) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> blogIds = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            blogIds.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (minTime == time) {
                os++;
            } else {
                minTime = Math.min(minTime == 0 ? time : minTime, time);
                os = 1;
            }
        }
        // 查数据库
        String idStr = StrUtil.join(",", blogIds);
        List<Blog> blogs = query().in("id", blogIds)
                .last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Blog blog : blogs) {
            User user = userService.getById(blog.getUserId());
            if (user != null) {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            }
            isBlogLiked(blog);
        }
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setMinTime(minTime);
        r.setOffset(os);
        return Result.ok(r);
    }

    @Override
    public Result queryBlogOfUser(Long userId, Integer current) {
        Page<Blog> page = query()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        for (Blog blog : records) {
            User user = userService.getById(blog.getUserId());
            if (user != null) {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            }
            isBlogLiked(blog);
        }
        return Result.ok(records);
    }

    /** 判断当前用户是否已点赞该笔记 */
    private void isBlogLiked(Blog blog) {
        try {
            UserDTO user = UserHolder.getUser();
            if (user == null) {
                blog.setIsLike(false);
                return;
            }
            Double score = stringRedisTemplate.opsForZSet()
                    .score(RedisConstants.BLOG_LIKED_KEY + blog.getId(), user.getId().toString());
            blog.setIsLike(score != null);
        } catch (Exception e) {
            blog.setIsLike(false);
        }
    }
}
