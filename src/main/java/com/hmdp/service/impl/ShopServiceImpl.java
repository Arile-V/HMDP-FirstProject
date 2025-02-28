package com.hmdp.service.impl;

//import cn.hutool.core.bean.BeanUtil;
//
//
//import cn.hutool.core.thread.ThreadUtil;
//import cn.hutool.core.util.RandomUtil;
//import cn.hutool.core.util.StrUtil;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
//import java.util.Map;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
//import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private CacheClient cacheClient;
    @Override
    public Result queryById(Long id){
        //Object shop = queryWithLogicExpire(id);// 先查热点数据再查普通数据
        Object shop = cacheClient.queryHotData(id,Shop.class,CACHE_SHOP_KEY, this::getById);
        if (shop != null) {
            return Result.ok(shop);
        }
        shop = cacheClient.getNormal(id,Shop.class,CACHE_SHOP_KEY,this::getById);
        //shop = queryWithMutex(id);
        if (shop == null) {
            return Result.fail("404");
        }

        return  Result.ok(shop);
    }

// TODO 下方注释掉的代码均为学习时针对此服务写的，现在使用包装好的Redis操作类来提高代码复用性
//    private Object queryInRedis(String strID) {
//        Map<Object, Object> shopMap1 = stringRedisTemplate.opsForHash().entries(CACHE_SHOP_KEY + strID);
//        if(shopMap1.containsValue("空对象")){
//            return null;
//        }
//        if (!shopMap1.isEmpty()&& shopMap1.containsKey("id")) {
//            String IdInMap = shopMap1.get("id").toString();
//            if (IdInMap.equals(strID)) {
//                return BeanUtil.fillBeanWithMap(shopMap1,new Shop(),false);
//            }
//        }
//        return null;
//    }
//    private Object queryWithMutex(Long id){
//        //  互斥锁防击穿
//        //  查Redis
//        //  如果无，获取锁，查库重建缓存并返回
//        String strID = id.toString();
//        Object shop = queryInRedis(strID);
//        if (!(shop == null)) {
//            log.debug("hello");
//            return shop;
//        }
//        try {
//            boolean isSuccess = tryLock("lock:shop:" + strID);
//            while (!isSuccess){
//                ThreadUtil.sleep(100); //已经有其他线程获取过了锁，因此休眠
//                shop = queryInRedis(strID);
//                if(!(shop ==null)){
//                    return shop;
//                }
//                isSuccess = tryLock("lock:shop:" + strID);
//                if (isSuccess){
//                    break;
//                }
//            }
//            shop = getById(id);
//            //  库中无，往缓存写null
//            if (shop == null) {
//                stringRedisTemplate.opsForHash().put(CACHE_SHOP_KEY + strID,"空对象","空对象");
//                return null;
//            }
//            log.debug("shop:{}", shop);
//            //  库中有，写缓存
//            Map<String, Object> shopMap = BeanUtil.beanToMap(shop);
//            shopMap.forEach((k, v) -> {
//                if(v!=null){
//                    v = v.toString();
//                    shopMap.put(k,v);
//                }
//            });
//            stringRedisTemplate.opsForHash().putAll(CACHE_SHOP_KEY + strID,shopMap);
//            stringRedisTemplate.expire(CACHE_SHOP_KEY + id,CACHE_SHOP_TTL+ RandomUtil.randomLong(0,CACHE_SHOP_TTL), TimeUnit.SECONDS);
//        } catch (Exception e) {
//            throw new RuntimeException(e);
//        } finally {
//            unlock("lock:shop:" + strID);
//        }
//        //  返回数据
//        log.debug("shop:{}", shop);
//        return shop;
//    }
//    private boolean tryLock(String key){
//        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",CACHE_SHOP_TTL*10, TimeUnit.SECONDS);
//        return Boolean.TRUE.equals(success);
//    }
//    private void unlock(String key){
//        stringRedisTemplate.delete(key);
//    }
//    private static final ExecutorService CACHE_REBUILD_POOL = Executors.newFixedThreadPool(5);// 线程池
//    public Object queryWithLogicExpire(Long id){
//        //  逻辑过期防击穿，本方法默认缓存已经预热过，里面预存了热点数据，于是不查数据库，只维护缓存
//        //  查Redis
//        //  如果无，说明查错了，返回
//        log.debug("queryWithLogicExpire:{}",id);
//        String strID = id.toString();
//        String strData = stringRedisTemplate.opsForValue().get(CACHE_SHOP_KEY + "hot" + strID);
//        if (StrUtil.isBlank(strData)) {
//            log.debug("no data");
//            return null;
//        }
//        RedisData<?> data = JSONUtil.toBean(strData, RedisData.class);
//        // 判断是否过期，未过期返回
//        if (LocalDateTime.now().isBefore(data.getTtl())){
//            log.debug("notGone:{}",id);
//            return data.getData();
//        }
//        Object shop = data.getData();
//        boolean isSuccess = tryLock("lock:shop:" + strID);
//        if (isSuccess){
//            if (LocalDateTime.now().isBefore(data.getTtl())){
//                log.debug("notGone2:{}",id);
//                return data.getData();
//            }
//            下方会启动一个新线程更新TTL写入缓存
//            CACHE_REBUILD_POOL.submit(() -> {
//                try {
//                    setHotData(id,10L);
//                } catch (Exception e) {
//                    throw new RuntimeException(e);
//                }finally {
//                    unlock("lock:shop:" + strID);
//                }
//            });
//        }
//        //  返回数据
//        log.debug("Return:{}",id);
//        return shop;
//    }

    @Override
    @Transactional //事务 TODO 面试前记得复习此机制
    public Result update(Shop shop) {
        //先检查数据合规性
        if (shop.getId() == null) {
            return Result.fail("数据不合规：ID不能为空");
        }
        // TODO 写一个主动更新缓存的数据库操作
        // 上传数据库
        updateById(shop);
        //因为是单体项目，所以丢进一个事务，如果是分布式，这里要用分布式的方法
        stringRedisTemplate.delete(CACHE_SHOP_KEY + shop.getId());
        // 删除对应缓存
        return Result.ok();
    }

    public void setHotData(Long id , Long TTL) throws InterruptedException {
        Thread.sleep(30L);
        Shop shop = getById(id);
        LocalDateTime time = LocalDateTime.now();
        RedisData<Shop> data = new RedisData<>(time.plusSeconds(TTL),shop);
        String json = JSONUtil.toJsonStr(data);
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + "hot" + id,json);
    }
}
