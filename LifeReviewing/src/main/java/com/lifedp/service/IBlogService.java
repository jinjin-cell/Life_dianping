package com.lifedp.service;

import com.lifedp.dto.Result;
import com.lifedp.dto.ScrollResult;
import com.lifedp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

public interface IBlogService extends IService<Blog> {

    /**
     * 查询博客详情（含用户信息、是否已点赞）
     */
    Result queryBlogById(Long id);

    /**
     * 点赞/取消点赞
     */
    Result likeBlog(Long id);

    /**
     * 查询点赞该博客的用户列表（top5）
     */
    Result queryBlogLikes(Long id);

    /**
     * 保存博客并推送给粉丝
     */
    Result saveBlog(Blog blog);

    /**
     * 滚动分页查询关注用户的博客（收件箱模式）
     */
    Result queryBlogOfFollow(Long max, Integer offset);

    /**
     * 查询指定用户的博客列表
     */
    Result queryBlogOfUser(Long userId, Integer current);
}
