package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSON;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TTL;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource private StringRedisTemplate stringRedisTemplate;
    @Override
    public Result getTypeList() {
        //TODO 查Redis，如果有返回，没就查表，查表后写缓存完返回
        String ListJson = stringRedisTemplate.opsForValue().get("shopType");
        if(StrUtil.isNotBlank(ListJson)){
            return Result.ok(JSONUtil.toList(ListJson, ShopType.class));
        }
        List<ShopType> typeList = query().orderByAsc("sort").list();
        ListJson = JSONUtil.toJsonStr(typeList);
        stringRedisTemplate.opsForValue().set("shopType",ListJson);
        stringRedisTemplate.expire("shopType",CACHE_SHOP_TTL, TimeUnit.SECONDS);
        return Result.ok(typeList);
    }
}
