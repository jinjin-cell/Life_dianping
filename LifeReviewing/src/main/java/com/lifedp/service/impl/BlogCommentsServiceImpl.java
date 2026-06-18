package com.lifedp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.lifedp.dto.Result;
import com.lifedp.dto.UserDTO;
import com.lifedp.entity.BlogComments;
import com.lifedp.entity.User;
import com.lifedp.mapper.BlogCommentsMapper;
import com.lifedp.service.IBlogCommentsService;
import com.lifedp.service.IUserService;
import com.lifedp.utils.UserHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class BlogCommentsServiceImpl extends ServiceImpl<BlogCommentsMapper, BlogComments> implements IBlogCommentsService {

    @Resource
    private IUserService userService;

    @Override
    public Result queryCommentsByBlogId(Long blogId, Integer page, Integer pageSize) {
        // 分页查一级评论（parentId = 0），按时间正序
        Page<BlogComments> commentPage = query()
                .eq("blog_id", blogId)
                .eq("parent_id", 0L)
                .orderByAsc("create_time")
                .page(new Page<>(page, pageSize));
        List<BlogComments> comments = commentPage.getRecords();
        if (CollectionUtil.isEmpty(comments)) {
            return Result.ok(Collections.emptyList());
        }
        // 收集评论用户ID
        List<Long> userIds = new ArrayList<>();
        for (BlogComments c : comments) {
            userIds.add(c.getUserId());
        }
        // 查每个一级评论的子回复
        List<Long> parentIds = comments.stream().map(BlogComments::getId).collect(Collectors.toList());
        List<BlogComments> replies = query()
                .eq("blog_id", blogId)
                .in("parent_id", parentIds)
                .orderByAsc("create_time")
                .list();
        for (BlogComments r : replies) {
            userIds.add(r.getUserId());
        }
        // 批量查用户信息
        Map<Long, UserDTO> userMap = getUserMap(userIds.stream().distinct().collect(Collectors.toList()));
        // 装配用户信息
        for (BlogComments c : comments) {
            UserDTO u = userMap.get(c.getUserId());
            if (u != null) {
                c.setNickName(u.getNickName());
                c.setIcon(u.getIcon());
            }
        }
        for (BlogComments r : replies) {
            UserDTO u = userMap.get(r.getUserId());
            if (u != null) {
                r.setNickName(u.getNickName());
                r.setIcon(u.getIcon());
            }
        }
        // 挂载子回复
        for (BlogComments c : comments) {
            c.setReplies(replies.stream()
                    .filter(r -> r.getParentId().equals(c.getId()))
                    .collect(Collectors.toList()));
        }
        return Result.ok(comments);
    }

    @Override
    @Transactional
    public Result addComment(BlogComments comment) {
        Long userId = UserHolder.getUser().getId();
        if (comment.getBlogId() == null) {
            return Result.fail("笔记ID不能为空");
        }
        if (StrUtil.isBlank(comment.getContent())) {
            return Result.fail("评论内容不能为空");
        }
        comment.setUserId(userId);
        comment.setCreateTime(LocalDateTime.now());
        comment.setUpdateTime(LocalDateTime.now());
        if (comment.getLiked() == null) comment.setLiked(0);
        if (comment.getStatus() == null) comment.setStatus(false);
        if (comment.getParentId() == null) comment.setParentId(0L);
        if (comment.getAnswerId() == null) comment.setAnswerId(0L);
        save(comment);
        return Result.ok(comment.getId());
    }

    @Override
    @Transactional
    public Result deleteComment(Long commentId) {
        Long userId = UserHolder.getUser().getId();
        BlogComments comment = getById(commentId);
        if (comment == null) {
            return Result.fail("评论不存在");
        }
        if (!comment.getUserId().equals(userId)) {
            return Result.fail("只能删除自己的评论");
        }
        // 一级评论同时删除子回复
        if (comment.getParentId() == 0) {
            query().eq("parent_id", commentId).list()
                    .forEach(c -> removeById(c.getId()));
        }
        removeById(commentId);
        return Result.ok();
    }

    @Override
    public Result likeComment(Long commentId) {
        update().setSql("liked = liked + 1").eq("id", commentId).update();
        return Result.ok();
    }

    private Map<Long, UserDTO> getUserMap(List<Long> userIds) {
        if (CollectionUtil.isEmpty(userIds)) {
            return Collections.emptyMap();
        }
        List<User> users = userService.listByIds(userIds);
        return users.stream().collect(Collectors.toMap(
                User::getId,
                user -> BeanUtil.copyProperties(user, UserDTO.class),
                (a, b) -> a
        ));
    }
}
