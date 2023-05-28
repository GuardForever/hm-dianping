package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {
    private StringRedisTemplate stringRedisTemplate;

    public RedisWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private final long Basic = 1640995200L;
    private final int size = 32;
    /**
     * 秒杀业务中所需要的额全局唯一id
     * @param prefix
     * @return
     */
    public long getUUid(String prefix){
        // 1.前31位为现在时间减去基准时间的秒数
        LocalDateTime now = LocalDateTime.now();
        long l = now.toEpochSecond(ZoneOffset.UTC) - Basic;
        // 2. 后32位位自增序列
        String format = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long increment = stringRedisTemplate.opsForValue().increment("inc" + prefix + format);
        // 3. 返回long值
        return l<<size | increment; // 将基准秒数左移32位 或运算 自增序列
    }

    public static void main(String[] args) {
        LocalDateTime of = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long l = of.toEpochSecond(ZoneOffset.UTC);
        System.out.println("时间"+l);
    }
}
