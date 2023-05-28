package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.RedisUtil;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisUtil redisUtil;

    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);

    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透问题
        //Shop shop = redisUtil.queryWithThrow(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,id2-> getById(id2),
        //        RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        // 解决缓存击穿问题
        Shop shop = redisUtil.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,RedisConstants.LOCK_SHOP_KEY,id,Shop.class,id2-> getById(id2),
                       RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return Result.ok(shop);
    }


    public Shop queryWithMutex(Long id){
        // 1.从redis中获取信息 存在返回
        String value = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2.根据id查询数据库 不存在返回404
        Shop shop = null;
        if (StrUtil.isNotEmpty(value)){
            return JSONUtil.toBean(value, Shop.class);
        }
        // 3.1获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        try {
            boolean syn = isSyn(StrUtil.toString(lockKey));
            // 3.2判断是否获取成功
            if(!syn){
                // 3.3失败 休眠并重试
                Thread.sleep(50);
                return queryWithMutex(id);
            }
            // 3.4成功根据id查询数据库
            shop = getById(id);
            if (shop==null){
                stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,
                        null,RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
                return shop;
            }
            // 4.将商铺信息写入Redis
            String redisValue = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,
                    redisValue,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException();
        } finally {
            delSyn(lockKey);
        }
        return shop;
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

    public Shop queryWithThrow(Long id){
        // 1.从redis中获取信息 存在返回
        String value = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2.根据id查询数据库 不存在返回404
        Shop shop = null;
        if (StrUtil.isNotEmpty(value)){
            return JSONUtil.toBean(value, Shop.class);
        }
        shop = getById(id);
        if (shop==null){
            // 为了解决缓存穿透问题  将null缓存到redis中  并设置过期时间
            stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,null,RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES);
            return shop;
        }
        // 3.将商铺信息写入Redis
        String redisValue = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,redisValue,RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }

    /**
     * 为了确保数据库与缓存一致性的问题：
     * 单系统的情况采用事务
     * 更新数据库后删除缓存：先更新数据库后删除缓存能更大限度的避免线程安全问题。因为更新数据库需要的时间较久
     * @param shop
     * @return
     */
    @Override
    @Transactional
    public Result updateShop(Shop shop) {
        Long id = shop.getId();
        if(id == null){
            return Result.fail("id不能为空");
        }
        // 1.先更新数据库
        updateById(shop);
        // 2.删除缓存
        stringRedisTemplate.delete(RedisConstants.LOCK_SHOP_KEY+id);
        return Result.ok();
    }

    public Shop queryWithLogicalExpire(Long id){
        // 1.从redis中获取信息
        String value = stringRedisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        // 2.根据id查询数据库 不存在返回null
        if (StrUtil.isEmpty(value)){
            return null;
        }
        // 3.存在 判断缓存是否过期
        RedisData redisData = JSONUtil.toBean(value, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        if (expireTime.isAfter(LocalDateTime.now())){
            // 3.1没过期
            return shop;}
        // 3.2已过期 获取互斥锁
        String lockKey = RedisConstants.LOCK_SHOP_KEY + id;
        if(isSyn(lockKey)){
            // 开启线程  添加key
            executorService.submit(()->{
                try {
                    saveShopRedis(id,10L);
                } catch (Exception e) {
                    throw new RuntimeException();
                } finally {delSyn(lockKey);}
            });
        }
        return shop;
    }

    /**
     * 保存缓存
     * @param id
     * @param time
     */
    public void saveShopRedis(Long id,Long time){
        // 1. 查询店铺数据
        Shop shop = getById(id);
        // 2. 封装逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(time));
        // 3. 写入redis
        stringRedisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
}
