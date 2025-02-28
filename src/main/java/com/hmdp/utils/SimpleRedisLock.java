package com.hmdp.utils;


import cn.hutool.core.lang.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;
@Slf4j
public class SimpleRedisLock implements ILock {
    private static final String  ID_PREFIX = UUID.fastUUID().toString(true)+"-";
    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final String KEY_PREFIX = "lock:";

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;//配好宏，减少文件io流，提高性能
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);//返回值
    }
    @Override
    public boolean tryLock(long timeOutSec) {
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        Boolean success = stringRedisTemplate
                .opsForValue()
                .setIfAbsent(KEY_PREFIX+name,threadId,timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        if(threadId.equals(stringRedisTemplate.opsForValue().get(KEY_PREFIX + name))){
//            stringRedisTemplate.delete(KEY_PREFIX+name);
//        }
        //调用Lua脚本实现原子化删除操作，在java当中就是一步，不会被GC阻塞阻挡
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX+name),
                ID_PREFIX + Thread.currentThread().getId());
    }
}
