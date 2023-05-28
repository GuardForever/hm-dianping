package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import io.lettuce.core.GeoArgs;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Redis的工具类
 */
@Component
public class RedisUtil {

    private final StringRedisTemplate stringRedisTemplate;

    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);
    // 构造函数注入
    public RedisUtil(StringRedisTemplate stringRedisTemplate){
        this.stringRedisTemplate = stringRedisTemplate;
    }

    /**
     * 存到缓存并设置TTL过期时间
     * @param key
     * @param object
     * @param time
     * @param unit
     */
    public void set(String key, Object object, Long time, TimeUnit unit){
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(object),time,unit);
    }

    /**
     * 设置逻辑过期时间
     * @param key
     * @param object
     * @param time
     * @param unit
     */
    public void setWithLogical(String key, Object object, Long time, TimeUnit unit){
        RedisData redisData = new RedisData();
        redisData.setData(object);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    /**
     *获得缓存并解决缓存穿透问题
     * @return
     */
    public <R,ID> R queryWithThrow(String prefix , ID id, Class<R> R, Function<ID,R> function,Long time, TimeUnit unit){
        // 1.从redis中获取信息 存在返回
        String key = prefix +id;
        String value = stringRedisTemplate.opsForValue().get(key);
        // 2.根据id查询数据库 不存在返回404
        R r = null;
        if (StrUtil.isNotEmpty(value)){
            return JSONUtil.toBean(value, R);
        }
        r = function.apply(id);
        if (r==null){
            // 为了解决缓存穿透问题  将null缓存到redis中  并设置过期时间
            this.set(key,null,time,unit);
            return r;
        }
        // 3.将商铺信息写入Redis
        String redisValue = JSONUtil.toJsonStr(r);
        this.set(key,r,time,unit);
        return r;
    }

    /**
     * 获取缓存并处理缓存击穿问题
     * @return
     */
    public <R,ID> R queryWithLogicalExpire(String prefix,String localpre,ID id,Class<R> R,
                                           Function<ID,R> function,Long time, TimeUnit unit){
        // 1.从redis中获取信息
        String key = prefix+id;
        String value = stringRedisTemplate.opsForValue().get(key);
        // 2.根据id查询数据库 不存在返回null
        if (StrUtil.isEmpty(value)){
            return null;
        }
        // 3.存在 判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(value, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject)redisData.getData();
        R r = JSONUtil.toBean(data, R);
        if (expireTime.isAfter(LocalDateTime.now())){
            // 3.1没过期
            return r;}
        // 3.2已过期 获取互斥锁
        String lockKey = localpre + id;
        if(isSyn(lockKey)){
            // 开启线程  添加key
            executorService.submit(()->{
                try {
                    this.setWithLogical(key,function.apply(id),time,unit);
                } catch (Exception e) {
                    throw new RuntimeException();
                } finally {delSyn(lockKey);}
            });
        }
        return r;
    }

    /**
     * 获取互斥锁
     * setnx效果  只有是空值的时候才会写入成功  非空值无法写入
     * @param key
     * @return
     */
    public boolean isSyn(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(key,"1",10,TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    /**
     * 释放互斥锁
     * 删除key
     */
    public void delSyn(String key){
        stringRedisTemplate.delete(key);
    }

}
