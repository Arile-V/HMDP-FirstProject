package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.hmdp.dto.UserDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.handler.HandlerInterceptorAdapter;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_TTL;
public class TokenInterceptor extends HandlerInterceptorAdapter {
    private final StringRedisTemplate redisTemplate;
    public TokenInterceptor(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String token = request.getHeader("authorization");

        if(StrUtil.isBlank(token)){
            return true;
        }

        Map<Object, Object> userDTOMap = redisTemplate.opsForHash().entries(LOGIN_USER_KEY+token);

        if (userDTOMap.isEmpty()) {
            return true;
        }
        //建立对象
        UserDTO user = BeanUtil.fillBeanWithMap(userDTOMap, new UserDTO(),false);
        //存进Thread当中
        UserHolder.saveUser(BeanUtil.toBean(user, UserDTO.class));
        //刷新Redis当中数据有效期
        redisTemplate.expire(LOGIN_USER_KEY+token,LOGIN_USER_TTL, TimeUnit.SECONDS);
        return true;
    }
}
