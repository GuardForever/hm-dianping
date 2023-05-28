package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private StringRedisTemplate stringRedisTemplate;
    private String key;
    private static final String KEY_PREIFX = "lock:";
    private static final String UUDI = UUID.randomUUID().toString(true);

    private static final DefaultRedisScript<Long> defaultRedisScript;
    static {
        defaultRedisScript = new DefaultRedisScript<>();
        defaultRedisScript.setLocation(new ClassPathResource("redisUnLock.lua"));
    }

    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String key) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.key = key;
    }

    /**
     * 尝试获取setnx锁
     * @param timeoutSec
     * @return
     */
    @Override
    public boolean tryLock(long timeoutSec) {
        String threadId = UUDI + Thread.currentThread().getId();
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(KEY_PREIFX + key,
                threadId, timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(result); // 避免拆箱的空指针异常
    }

    /**
     * 释放锁
     */
    @Override
    public void unLock() {
        // 通过lua脚本释放锁 保证原子性
        String threadId = UUDI + Thread.currentThread().getId();
        stringRedisTemplate.execute(defaultRedisScript,Collections.singletonList(KEY_PREIFX + key),threadId);
        // 判断获得的锁是否是自己的线程
        /*String threadId = UUDI + Thread.currentThread().getId();
        String trhead = stringRedisTemplate.opsForValue().get(KEY_PREIFX + key);
        if(threadId.equals(trhead)){ // 是自己的线程才会释放
            stringRedisTemplate.delete(KEY_PREIFX+key);
        }*/
    }
}
