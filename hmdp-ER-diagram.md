# HMD数据库 ER 图 (大众点评项目)

## 表关系总览 (11 张表)

```mermaid
erDiagram
    tb_user ||--|| tb_user_info : "user_id 1:1"
    tb_user ||--o{ tb_blog : "user_id 发表探店笔记"
    tb_user ||--o{ tb_blog_comments : "user_id 发表评论"
    tb_user ||--o{ tb_follow : "user_id 关注"
    tb_user ||--o{ tb_follow : "follow_user_id 被关注"
    tb_user ||--o{ tb_voucher_order : "user_id 下单"
    tb_user ||--o{ tb_sign : "user_id 签到"

    tb_shop_type ||--o{ tb_shop : "type_id 1:N"
    tb_shop ||--o{ tb_blog : "shop_id 探店笔记"
    tb_shop ||--o{ tb_voucher : "shop_id 发放优惠券"

    tb_blog ||--o{ tb_blog_comments : "blog_id 评论"
    tb_blog_comments ||--o{ tb_blog_comments : "parent_id 嵌套回复"

    tb_voucher ||--o| tb_seckill_voucher : "voucher_id 秒杀券 1:1"
    tb_voucher ||--o{ tb_voucher_order : "voucher_id 购买订单"

    tb_user {
        bigint id PK "主键 自增"
        varchar phone UK "手机号码 11位"
        varchar password "密码 加密存储"
        varchar nick_name "昵称"
        varchar icon "用户头像URL"
        timestamp create_time "创建时间"
        timestamp update_time "更新时间"
    }

    tb_user_info {
        bigint user_id PK "主键 等同于tb_user.id"
        varchar city "城市名称"
        varchar introduce "个人介绍 ≤128字符"
        int fans "粉丝数量"
        int followee "关注数量"
        tinyint gender "0男 1女"
        date birthday "生日"
        int credits "积分"
        tinyint level "会员级别 0~9"
        timestamp create_time "创建时间"
        timestamp update_time "更新时间"
    }

    tb_shop_type {
        bigint id PK "主键 自增"
        varchar name "类型名称 如美食 KTV"
        varchar icon "图标URL"
        int sort "排序序号"
        timestamp create_time "创建时间"
        timestamp update_time "更新时间"
    }

    tb_shop {
        bigint id PK "主键 自增"
        varchar name "商铺名称"
        bigint type_id FK "商铺类型ID → tb_shop_type.id"
        varchar images "商铺图片 逗号分隔"
        varchar area "商圈 如陆家嘴"
        varchar address "详细地址"
        double x "经度"
        double y "纬度"
        bigint avg_price "均价 单位元"
        int sold "销量"
        int comments "评论数量"
        int score "评分 1~5乘10存储"
        varchar open_hours "营业时间 如10:00-22:00"
        timestamp create_time "创建时间"
        timestamp update_time "更新时间"
    }

    tb_blog {
        bigint id PK "主键 自增"
        bigint shop_id FK "商户ID → tb_shop.id"
        bigint user_id FK "用户ID → tb_user.id"
        varchar title "标题"
        varchar images "探店照片 最多9张 逗号分隔"
        varchar content "探店文字描述"
        int liked "点赞数量"
        int comments "评论数量"
        timestamp create_time "创建时间"
        timestamp update_time "更新时间"
    }

    tb_blog_comments {
        bigint id PK "主键 自增"
        bigint user_id FK "用户ID → tb_user.id"
        bigint blog_id FK "探店ID → tb_blog.id"
        bigint parent_id "父评论ID 一级评论为0"
        bigint answer_id "回复的目标评论ID"
        varchar content "回复内容"
        int liked "点赞数"
        tinyint status "0正常 1被举报 2禁止查看"
        timestamp create_time "创建时间"
        timestamp update_time "更新时间"
    }

    tb_follow {
        bigint id PK "主键 自增"
        bigint user_id FK "用户ID → tb_user.id"
        bigint follow_user_id FK "被关注用户ID → tb_user.id"
        timestamp create_time "创建时间"
    }

    tb_voucher {
        bigint id PK "主键 自增"
        bigint shop_id FK "商铺ID → tb_shop.id"
        varchar title "代金券标题"
        varchar sub_title "副标题"
        varchar rules "使用规则"
        bigint pay_value "支付金额 单位分"
        bigint actual_value "抵扣金额 单位分"
        tinyint type "0普通券 1秒杀券"
        tinyint status "1上架 2下架 3过期"
        timestamp create_time "创建时间"
        timestamp update_time "更新时间"
    }

    tb_seckill_voucher {
        bigint voucher_id PK "关联优惠券ID → tb_voucher.id"
        int stock "库存数量"
        timestamp begin_time "秒杀开始时间"
        timestamp end_time "秒杀结束时间"
        timestamp create_time "创建时间"
        timestamp update_time "更新时间"
    }

    tb_voucher_order {
        bigint id PK "主键 雪花算法生成"
        bigint user_id FK "用户ID → tb_user.id"
        bigint voucher_id FK "代金券ID → tb_voucher.id"
        tinyint pay_type "1余额 2支付宝 3微信"
        tinyint status "1未支付 2已支付 3已核销 4已取消 5退款中 6已退款"
        timestamp create_time "下单时间"
        timestamp pay_time "支付时间"
        timestamp use_time "核销时间"
        timestamp refund_time "退款时间"
        timestamp update_time "更新时间"
    }

    tb_sign {
        bigint id PK "主键 自增"
        bigint user_id FK "用户ID → tb_user.id"
        year year "签到年份"
        tinyint month "签到月份"
        date date "签到日期"
        tinyint is_backup "是否补签"
    }
```

## 业务关系说明

| 关系 | 类型 | 说明 |
|------|------|------|
| tb_user ↔ tb_user_info | **1:1** | 每个用户有一份详细资料，user_info.user_id = user.id |
| tb_user → tb_blog | **1:N** | 一个用户可发表多篇探店笔记 |
| tb_user → tb_blog_comments | **1:N** | 一个用户可发表多条评论 |
| tb_user → tb_follow | **1:N** | 一个用户可关注多个其他用户 (user_id) |
| tb_user → tb_follow | **1:N** | 一个用户可被多个其他用户关注 (follow_user_id) |
| tb_user → tb_voucher_order | **1:N** | 一个用户可下多个优惠券订单 |
| tb_user → tb_sign | **1:N** | 一个用户有多条签到记录 |
| tb_shop_type → tb_shop | **1:N** | 一个商铺类型下有多个商铺 |
| tb_shop → tb_blog | **1:N** | 一个商铺可被多次探店写笔记 |
| tb_shop → tb_voucher | **1:N** | 一个商铺可发放多种优惠券 |
| tb_blog → tb_blog_comments | **1:N** | 一篇笔记下有多条评论 |
| tb_blog_comments → tb_blog_comments | **自引用 1:N** | 评论支持嵌套回复 (parent_id) |
| tb_voucher → tb_seckill_voucher | **1:1** | type=1的优惠券有秒杀扩展属性 |
| tb_voucher → tb_voucher_order | **1:N** | 一种优惠券可被多次购买 |

## 核心业务流程

```
用户(tb_user) → 浏览商铺(tb_shop by tb_shop_type分类)
              → 写探店笔记(tb_blog) → 评论互动(tb_blog_comments)
              → 关注其他用户(tb_follow)
              → 购买优惠券(tb_voucher_order ← tb_voucher ← tb_seckill_voucher)
              → 每日签到(tb_sign)
```
