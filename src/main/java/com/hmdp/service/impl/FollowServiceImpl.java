package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Resource
    IUserService userService;
    @Override
    public Result follow(Long id, boolean isFollow) {
        Long userId = UserHolder.getUser().getId();
        if (isFollow) {
            Follow follow = new Follow();
            follow.setId(userId);
            follow.setFollowUserId(id);
            boolean success = save(follow);
            if (success) {
                String redisKey = "follow:" + userId.toString();
                stringRedisTemplate.opsForSet().add(redisKey, id.toString());
            }
        }else {
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("user_id",userId)
                    .eq("follow_user_id",id));
            if (success) {
                String redisKey = "follow:" + userId.toString();
                stringRedisTemplate.opsForSet().remove(redisKey, id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        Long userId = UserHolder.getUser().getId();
        Integer num = query().eq("user_id",userId).eq("follow_user_id",id).count();
        if (num > 0) {
            return Result.ok(true);
        }else {
            return Result.ok(false);
        }
    }

    @Override
    public Result common(Long id) {
        Long userId = UserHolder.getUser().getId();
        String redisKey1 = "follow:" + userId.toString();
        String redisKey2 = "follow:" + id.toString();
        Set<String> commonFollow = stringRedisTemplate.opsForSet().intersect(redisKey1,redisKey2);
        if (commonFollow==null||commonFollow.size()==0){
            return Result.ok(Collections.emptyList());
        }
        List<Long> ids = commonFollow.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(user -> BeanUtil.copyProperties(user,UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
