package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
@Component
public class RedisIdWorker {
    private StringRedisTemplate stringRedisTemplate;
    public RedisIdWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    private static final Long BEGIN_TIMESTAMP = 1737894710L;
    private static final Integer COUNT_BIT = 32;

    public Long nextId(String prefix) {
        Long nowSecond = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        Long timeStamp = nowSecond - BEGIN_TIMESTAMP;

        String date = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        Long count = stringRedisTemplate.opsForValue().increment("icr:"+prefix+":"+date);
        return timeStamp << COUNT_BIT | count;
    }

}
