package com.lifedp.controller;

import com.lifedp.dto.Result;
import com.lifedp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    /** 关注或取关 */
    @PutMapping("/{id}/{isFollow}")
    public Result follow(@PathVariable("id") Long followUserId,
                         @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    /** 判断是否已关注 */
    @GetMapping("/or/not/{id}")
    public Result isFollow(@PathVariable("id") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    /** 共同关注 */
    @GetMapping("/common/{id}")
    public Result followCommons(@PathVariable("id") Long id) {
        return followService.followCommons(id);
    }

    /** 粉丝列表（含用户信息） */
    @GetMapping("/fans/{userId}")
    public Result queryFans(@PathVariable("userId") Long userId) {
        return followService.queryFansWithInfo(userId);
    }

    /** 关注列表（含用户信息） */
    @GetMapping("/follows/{userId}")
    public Result queryFollows(@PathVariable("userId") Long userId) {
        return followService.queryFollowsWithInfo(userId);
    }
}
