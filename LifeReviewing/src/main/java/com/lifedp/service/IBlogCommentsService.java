package com.lifedp.service;

import com.lifedp.dto.Result;
import com.lifedp.entity.BlogComments;
import com.baomidou.mybatisplus.extension.service.IService;

public interface IBlogCommentsService extends IService<BlogComments> {

    /** 分页查询笔记的评论列表（含用户昵称头像、子回复） */
    Result queryCommentsByBlogId(Long blogId, Integer page, Integer pageSize);

    /** 发表评论或回复 */
    Result addComment(BlogComments comment);

    /** 删除评论（仅本人可删） */
    Result deleteComment(Long commentId);

    /** 点赞/取消点赞 */
    Result likeComment(Long commentId);
}
