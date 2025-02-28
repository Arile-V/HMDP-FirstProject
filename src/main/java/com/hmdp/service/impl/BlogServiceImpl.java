package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;
import static com.hmdp.utils.RedisConstants.FEED_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IFollowService followService;
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource private RedissonClient redissonClient;
    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> likedSet = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY+id, 0, 4);
        if (likedSet == null || likedSet.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        List<Long> userIdList = likedSet.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr = StrUtil.join(",", userIdList);
        List<UserDTO> users = userService.query()
                .in("id",userIdList)
                .last("ORDER BY FIELD(id,"+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }

    @Override
    public Result saveBlog(Blog blog) {
        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean success = save(blog);
        if(!success){
            return Result.fail("上传失败，稍后再试");
        }
        //查粉丝
        List<Long> ids = followService.query().eq("follow_user_id", user.getId()).list()
                .stream().map(Follow::getId).collect(Collectors.toList());
//        List<UserDTO> users = userService.listByIds(ids).stream()
//                .map(u -> BeanUtil.copyProperties(u, UserDTO.class))
//                .collect(Collectors.toList());
        //推送
        ids.forEach(id->{
            String Key = FEED_KEY + id;
            stringRedisTemplate.opsForZSet().add(Key, blog.getId().toString(), System.currentTimeMillis());
        });
        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //五个步骤：1、获取当前用户；2、查询收件箱；3、解析数据得到minTime和offset；4、得到Blog集合；5封装到ScrollResult中并返回
        Long userId = UserHolder.getUser().getId();
        String redisKey = BLOG_LIKED_KEY + userId;
        Set<ZSetOperations.TypedTuple<String>> likeSet = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(redisKey, 0, max,offset,3);
        if (likeSet == null || likeSet.isEmpty()) {
            return Result.ok(Collections.emptyList());
        }
        //解析
        List<Long> ids = new ArrayList<>(likeSet.size());
        long minTime = 0;
        int os = 1;
        for (ZSetOperations.TypedTuple<String> item : likeSet) {
            String id = item.getValue();
            if (id != null) {
                ids.add(Long.parseLong(id));
            }
            if(item.getScore().longValue() == minTime){
                os++;
            } else {
                minTime = item.getScore().longValue();
                os = 1;
            }
        }
        if (minTime==max){
            os+=offset;
        }
        //查Blog
        String idStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("ORDER BY FIELD(id,"+idStr+")").list();
        for (Blog blog : blogs) {
            isBlogLiked(blog);
            User user = userService.getById(blog.getUserId());
            blog.setIcon(user.getIcon());
            blog.setName(user.getNickName());
        }
        ScrollResult r = new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }

    @Override
    public Result queryBlogById(long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("没有对应的笔记");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    public Result queryHotBlog(Integer current){
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog -> {
            this.queryBlogUser(blog);
            this.isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    private void isBlogLiked(Blog blog) {
        Long userId = blog.getUserId();
        Long BlogId = blog.getId();
        String id = BlogId.toString();
        Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY+id, userId.toString());
        boolean isLiked = (score!=null);
        blog.setIsLike(BooleanUtil.isTrue(isLiked));
    }

    @Override
    public Result likeBlogById(Long id) {
        //判断是否点赞-如果没有，加一，写Redis的Set，如果有就反着来
        UserDTO user = UserHolder.getUser();
        String userId = user.getId().toString();
        RLock lock = redissonClient.getLock(BLOG_LIKED_KEY+id+":user:"+userId);
        boolean locked = lock.tryLock();
        if (locked) {
            try{
                Double score = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY+id, userId);
                boolean isLiked = (score!=null);
                if (Boolean.TRUE.equals(isLiked)) {
                    update().setSql("liked = liked - 1").eq("id", id).update();
                    stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY+id, userId);
                }else {
                    boolean success = update().setSql("liked = liked + 1").eq("id", id).update();
                    if(success){
                        stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY+id, userId, System.currentTimeMillis());
                    }
                }
                return Result.ok();
            }finally {
                lock.unlock();
            }
        }else {
            return Result.fail("服务器繁忙");
        }
    }

    private void queryBlogUser(Blog blog){
        Long userId = blog.getUserId();
        User user = userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }
}
