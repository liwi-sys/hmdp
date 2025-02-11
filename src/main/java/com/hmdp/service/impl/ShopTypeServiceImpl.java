package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
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
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    public Result queryTypeList() {
        //1.从redis中查询店铺类型列表
        String key = "cache:shopType";
        List<String> shopTypeJsonList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2.存在则直接返回
        if (!shopTypeJsonList.isEmpty()) {
            List<ShopType> shopTypeList = shopTypeJsonList
                    .stream().map(jsonStr -> JSONUtil.toBean(jsonStr, ShopType.class)).collect(Collectors.toList());
            return Result.ok(shopTypeList);
        }
        //3.不存在则查询数据库
        List<ShopType> shopTypeList = query().orderByAsc("sort").list();
        //4.不存在则返回错误
        if (shopTypeList.isEmpty()) {
            return Result.fail("店铺类型列表不存在！");
        }
        //5.存在则写入redis
        List<String> shopTypeCacheJsonList = shopTypeList.stream().map(shopType -> JSONUtil.toJsonStr(shopType)).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(key,shopTypeCacheJsonList);
        //6.返回
        return Result.ok(shopTypeList);
    }
}
