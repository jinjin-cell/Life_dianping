package com.lifedp.controller;

import com.lifedp.dto.Result;
import com.lifedp.entity.BlogComments;
import com.lifedp.service.IBlogCommentsService;
import com.lifedp.utils.SystemConstants;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/blog-comments")
public class BlogCommentsController {

    @Resource
    private IBlogCommentsService blogCommentsService;

    /** 分页查询笔记评论 */
    @GetMapping("/of/{blogId}")
    public Result queryCommentsByBlogId(
            @PathVariable("blogId") Long blogId,
            @RequestParam(value = "current", defaultValue = "1") Integer current) {
        return blogCommentsService.queryCommentsByBlogId(blogId, current, SystemConstants.DEFAULT_PAGE_SIZE);
    }

    /** 发表评论或回复 */
    @PostMapping
    public Result addComment(@RequestBody BlogComments comment) {
        return blogCommentsService.addComment(comment);
    }

    /** 删除评论 */
    @DeleteMapping("/{id}")
    public Result deleteComment(@PathVariable("id") Long id) {
        return blogCommentsService.deleteComment(id);
    }

    /** 点赞评论 */
    @PutMapping("/like/{id}")
    public Result likeComment(@PathVariable("id") Long id) {
        return blogCommentsService.likeComment(id);
    }
}
