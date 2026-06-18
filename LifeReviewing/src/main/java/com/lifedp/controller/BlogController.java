package com.lifedp.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lifedp.dto.Result;
import com.lifedp.dto.UserDTO;
import com.lifedp.entity.Blog;
import com.lifedp.entity.User;
import com.lifedp.service.IBlogService;
import com.lifedp.service.IUserService;
import com.lifedp.utils.RedisConstants;
import com.lifedp.utils.SystemConstants;
import com.lifedp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;
import java.util.List;

@RestController
@RequestMapping("/blog")
public class BlogController {

    @Resource
    private IBlogService blogService;
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /** 发布笔记（推送给粉丝收件箱） */
    @PostMapping
    public Result saveBlog(@RequestBody Blog blog) {
        return blogService.saveBlog(blog);
    }

    /** 笔记详情（含用户信息、是否已点赞） */
    @GetMapping("/{id}")
    public Result queryBlogById(@PathVariable("id") Long id) {
        return blogService.queryBlogById(id);
    }

    /** 点赞/取消点赞（Redis ZSET 切换） */
    @PutMapping("/like/{id}")
    public Result likeBlog(@PathVariable("id") Long id) {
        return blogService.likeBlog(id);
    }

    /** 查询点赞该笔记的用户列表（top5） */
    @GetMapping("/likes/{id}")
    public Result queryBlogLikes(@PathVariable("id") Long id) {
        return blogService.queryBlogLikes(id);
    }

    /** 我的笔记 */
    @GetMapping("/of/me")
    public Result queryMyBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        UserDTO user = UserHolder.getUser();
        Page<Blog> page = blogService.query()
                .eq("user_id", user.getId())
                .orderByDesc("create_time")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        return Result.ok(records);
    }

    /** 大厅笔记（按发布时间倒序，返回全部） */
    @GetMapping("/hot")
    public Result queryHotBlog(@RequestParam(value = "current", defaultValue = "1") Integer current) {
        List<Blog> records = blogService.query()
                .orderByDesc("create_time")
                .list();
        records.forEach(blog -> {
            Long userId = blog.getUserId();
            User user = userService.getById(userId);
            if (user != null) {
                blog.setName(user.getNickName());
                blog.setIcon(user.getIcon());
            } else {
                blog.setName("已注销");
                blog.setIcon("");
            }
        });
        return Result.ok(records);
    }

    /** 关注用户的笔记（收件箱滚动分页） */
    @GetMapping("/of/follow")
    public Result queryBlogOfFollow(
            @RequestParam(value = "lastId", required = false) Long max,
            @RequestParam(value = "offset", defaultValue = "0") Integer offset) {
        return blogService.queryBlogOfFollow(max, offset);
    }

    /** 指定用户的笔记 */
    @GetMapping("/of/user")
    public Result queryBlogOfUser(
            @RequestParam("id") Long userId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogService.queryBlogOfUser(userId, current);
    }
}
