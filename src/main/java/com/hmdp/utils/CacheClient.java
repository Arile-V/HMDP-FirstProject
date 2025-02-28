package com.hmdp.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.RandomUtil;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;


import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;


import java.time.LocalDateTime;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Slf4j
@Component
public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    // TODO 写四个方法，两个存入可以将任意Java对象序列化为Json字符串，一个可以设置TTL，一个可以设置逻辑过期时间，两个查询，一个实现普通查询，另外一个是逻辑过期查询
    public void set(Object data, String key , Long time , TimeUnit timeUnit) {
        data = data.getClass();
        String json = JSONUtil.toJsonStr(data);
        stringRedisTemplate.opsForValue().set(key, json, time, timeUnit);
    }
    public void setHot(Object data, String key, Long time , TimeUnit timeUnit) {
        RedisData<?> redisData = new RedisData<>(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)),data);
        String json = JSONUtil.toJsonStr(redisData);
        stringRedisTemplate.opsForValue().set(key, json);
    }

    public <R,ID> R getNormal(ID id, Class<R> clazz, String prefix, Function<ID,R> DBfunction) {
        //  互斥锁防击穿
        //  查Redis
        //  如果无，获取锁，查库重建缓存并返回
        String strID = id.toString();
        R data = queryInRedis(strID,clazz,prefix);
        log.debug("success1");
        if (!(data == null)) {
            return data;
        }
        try {
            boolean isSuccess = tryLock(prefix+ "lock:" + strID);
            while (!isSuccess){
                ThreadUtil.sleep(100); //已经有其他线程获取过了锁，因此休眠
                data = queryInRedis(strID,clazz,prefix);
                log.debug("success2");
                if(!(data ==null)){
                    return data;
                }
                isSuccess = tryLock(prefix + "lock:" + strID);
                log.debug("success3");
                if (isSuccess){
                    break;
                }
            }
            log.debug("success4");
            data = DBfunction.apply(id);
            log.debug("success5");
            //  库中无，往缓存写null
            if (data == null) {
                stringRedisTemplate.opsForHash().put(prefix + strID,"空对象","空对象");
                return null;
            }
            log.debug("data:{}", data);
            //  库中有，写缓存
            Map<String, Object> RMap = BeanUtil.beanToMap(data);
            RMap.forEach((k, v) -> {
                if(v!=null){
                    v = v.toString();
                    RMap.put(k,v);
                }
            });

            stringRedisTemplate.opsForHash().putAll(prefix + strID,RMap);
            stringRedisTemplate.expire(prefix + id,CACHE_SHOP_TTL+ RandomUtil.randomLong(0,CACHE_SHOP_TTL), TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            unlock(prefix + "lock:" + strID);
        }
        //  返回数据
        return data;
    }
    private boolean tryLock(String key){
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",CACHE_SHOP_TTL*10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
    private <R> R queryInRedis(String strID,Class<R> clazz,String prefix)  {
        Map<Object, Object> shopMap1 = stringRedisTemplate.opsForHash().entries(prefix + strID);
        if(shopMap1.containsValue("空对象")){
            return null;
        }
        if (!shopMap1.isEmpty()&& shopMap1.containsKey("id")) {
            String IdInMap = shopMap1.get("id").toString();
            if (IdInMap.equals(strID)) {
                CopyOptions opts = CopyOptions.create();
                return BeanUtil.toBean(shopMap1,clazz,opts);
            }
        }
        return null;
    }

    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(5);
    public <R,ID> R queryHotData(ID id, Class<R> clazz, String prefix, Function<ID,R> DBfunction){
        //  逻辑过期防击穿，本方法默认缓存已经预热过，里面预存了热点数据，于是不查数据库，只维护缓存
        //  查Redis
        //  如果无，说明查错了，返回
        // TODO 今天改完
        log.debug("queryWithLogicExpire:{}",id);
        String strID = id.toString();
        String strData = stringRedisTemplate.opsForValue().get(prefix + "hot" + strID);
        if (StrUtil.isBlank(strData)) {
            log.debug("no data");
            return null;
        }
        RedisData<?> data = JSONUtil.toBean(strData, RedisData.class);
        // 判断是否过期，未过期返回
        if (LocalDateTime.now().isBefore(data.getTtl())){
            Object obj = data.getData();
            log.debug("Shop:notGone:{}",id);
            return BeanUtil.toBean(obj,clazz);
        }
        Object obj = data.getData();
        R Result = BeanUtil.toBean(obj,clazz);
        boolean isSuccess = tryLock("lock:" + prefix + strID);
        if (isSuccess){
            if (LocalDateTime.now().isBefore(data.getTtl())){
                log.debug("Locked:{}",id);
                obj = data.getData();
                return BeanUtil.toBean(obj,clazz);
            }
            // TODO 下方会启动一个新线程更新TTL写入缓存
            CACHE_REBUILD_POOL.submit(() -> {
                try {
                    setHotData(id,10L,DBfunction,prefix);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    unlock("lock:" + prefix + strID);
                }
            });
        }
        //  返回数据
        log.debug("Return:{}",id);
        return Result;
    }
    public<R,ID> void setHotData(ID id , Long TTL ,Function<ID,R> DBfunction,String prefix) throws InterruptedException {
        Thread.sleep(30L);
        R data2Redis = DBfunction.apply(id);
        LocalDateTime time = LocalDateTime.now();
        RedisData<R> data = new RedisData<>(time.plusSeconds(TTL),data2Redis);
        String json = JSONUtil.toJsonStr(data);
        stringRedisTemplate.opsForValue().set(prefix + "hot" + id,json);
    }
}
