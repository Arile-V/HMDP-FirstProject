package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result SendCode(String phone) {
        //校验
        if (RegexUtils.isPhoneInvalid(phone)) {
            return Result.fail("手机号格式错误");
        }
        //生成验证码
        String code = RandomUtil.randomNumbers(6);
        //保存验证码
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone,code,LOGIN_CODE_TTL, TimeUnit.MINUTES);
        //发送验证码（此处用日志模拟）
        log.debug("发送验证码{}",code);
        return Result.ok("验证码已发送");
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误");
        }
        String CacheCode = (String)stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY + phone);
        if (CacheCode == null || !CacheCode.equals(loginForm.getCode())){
            return Result.fail("验证码错误");
        }

        User user = query().eq("phone", phone).one();
        if (user == null){
            user = CreateUserWithPhone(phone);
        }
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        //生成Token并将其存入redis当中
        String token = UUID.randomUUID(true).toString();
        Map<String, Object> UserDTOMap = BeanUtil.beanToMap(userDTO,new HashMap<>(),
                CopyOptions.create()
                        .ignoreNullValue()
                        .setFieldValueEditor((fieldName,fieldValue)->(fieldValue.toString()))
        );
//        Long id = (Long)UserDTOMap.get("id");
//        String StrID = Long.toString(id);
//        UserDTOMap.replace("id",id,StrID);
        stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token,UserDTOMap);

        stringRedisTemplate.expire(LOGIN_USER_KEY + token,LOGIN_USER_TTL+RandomUtil.randomLong(0L,LOGIN_USER_TTL/60L), TimeUnit.MINUTES);

//        session.setAttribute("user", BeanUtil.copyProperties(user, UserDTO.class));

        return Result.ok(token);
    }

    public Result logout(HttpServletRequest request){
        String token = request.getHeader("token");
        stringRedisTemplate.delete(LOGIN_USER_KEY+token);
        return Result.ok("登出成功");
    }

    @Override
    public Result signCount() {
        String userId = UserHolder.getUser().getId().toString();
        LocalDateTime localDateTime = LocalDateTime.now();
        String dateKey = localDateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String signKey = USER_SIGN_KEY + userId + dateKey;
        int day = localDateTime.toLocalDate().getDayOfMonth();
        //多的两步：1、获取本月签到天数，返回的是转化为十进制的二进制数；2、循环遍历二进制位，对这个位上的数进行与运算
        List<Long> nums = stringRedisTemplate.opsForValue()
                .bitField(signKey, BitFieldSubCommands.create()
                        .get(BitFieldSubCommands.BitFieldType.signed(day - 1))
                        .valueAt(0));
        if(nums==null||nums.isEmpty()){
            return Result.ok(0);
        }
        Long num = nums.get(0);
        if(num==null||num==0){
            return Result.ok(0);
        }
        int count = 0;
        while ((num & 1) != 0) {
            count++;
            num >>>= 1;
        }
        return Result.ok(count);
    }

    @Override
    public Result sign() {
        //1、获取用户id；2、获取当前日期；3、拼接key；4、获取当前日期是月中第几天；5、存Redis；
        String userId = UserHolder.getUser().getId().toString();
        LocalDateTime localDateTime = LocalDateTime.now();
        String dateKey = localDateTime.format(DateTimeFormatter.ofPattern(":yyyyMM"));
        String signKey = USER_SIGN_KEY + userId + dateKey;
        int day = localDateTime.toLocalDate().getDayOfMonth();
        stringRedisTemplate.opsForValue().setBit(signKey,day - 1,true);
        return Result.ok();
    }


    private User CreateUserWithPhone(String phone){
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomString(6));
        save(user);
        return user;
    }
}
