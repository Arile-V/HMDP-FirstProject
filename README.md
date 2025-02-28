# 一个网络开源JavaWeb项目我的后端实现

## 技术栈及功能介绍

用Redis+Token的方案制作了登录和登录退出功能，发给前端的Token是一串唯一id，Redis当中存入一个有有效期属性的key作为用户登录状态标识，用户请求携带这个Token，用户登出请求直接删除这个表示登录状态的Key

用Redis制作了页面缓存，并且使用分布式锁保证了缓存与数据库之间的数据一致性，解决了缓存击穿，缓存穿透，缓存雪崩三个问题

制作了抢购功能，商品数据同时存入Redis和数据库，前端请求只会同步更改Redis当中提前存入的数据，完成更改后利用RabbitMQ发送消息携带对应参数调用方法将数据更改同步到数据库，提高了响应速度

使用Redis的bitMap等实现了签到统计

实现了博客及其点赞评论功能

## 接下来的规划

接下来将会尝试实现布隆过滤器

预计使用bitMap与哈希算法实现：
        消息请求经过哈希算法转为一个二进制值去和bitMap维护的二进制数进行比对，如果全部击中有值的部分则认为请求可能有效，予以放行，如果出现击中任何一个值为0的部分则认定请求无效
