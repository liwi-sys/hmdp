package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    //可设置缓存TTL时间
    public void set(String key, Object value, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    //可设置逻辑淘汰时间
    public void setLogicalExpire(String key,Object value, Long time,TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));//设置逻辑淘汰时间
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(redisData));//不再设置TTL时间
    }

    public <ID,R> R queryWithPassThrough(
            String keyPrefix, ID id, Class<R> type, Function<ID,R> dbFallback,Long time,TimeUnit unit) {
        //1.从redis查询缓存
        String key = keyPrefix + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在缓存
        if (StrUtil.isNotBlank(jsonStr)) {//非null，且非空字符串“”时，才返回
            //3.存在，直接返回
            return JSONUtil.toBean(jsonStr, type);
        }
        //为空字符串“”时也返回
        if (jsonStr != null) {
            return null;
        }
        //4.不存在，根据id查询数据库
        R r = dbFallback.apply(id);
        //5.不存在，返回错误
        if (r == null) {
            //在缓存中存入空字符串，以解决缓存穿透问题
            this.set(key, "", time, unit);
            return null;
        }
        //6.存在，写入redis
        this.set(key, JSONUtil.toJsonStr(r), time, unit);
        //7.返回
        return r;
    }
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    //逻辑过期解决缓存击穿（默认热点数据已经预热，即已经存入redis中）
    public <ID,R> R queryWithLogicalExpire(
            String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback,Long time,TimeUnit unit) {
        //1.从redis查询缓存
        String key = keyPrefix + id;
        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在缓存
        if (StrUtil.isBlank(jsonStr)) {
            //3.不存在，返回null
            return null;
        }
        //4.命中，先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(jsonStr, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);//还需将返回的JSONObject转为具体的对象
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回店铺信息
            return r;
        }
        //5.2过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String keyLock = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(keyLock);
        //6.2判断是否获取锁成功
        if (isLock) {
            // 6.3成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写redis
                    this.setLogicalExpire(key,r1,time,unit);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(keyLock);
                }
            });

        }
        //6.4返回过期的商铺信息
        return r;
    }
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
