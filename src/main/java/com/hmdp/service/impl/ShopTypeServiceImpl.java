package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.apache.tomcat.util.json.JSONParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

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

    @Autowired
    private RedisTemplate redisTemplate;
    /**
     * Redis缓存策略
     * @return
     */
    @Override
    public Result queryTypeList() {
        // 1.判断redis中是否存在 存在及返回 采用list类型
        Long size = redisTemplate.opsForList().size(RedisConstants.TYPE_LIST_KEY);
        if(size != null && size > 0){
            List<ShopType> typeList = redisTemplate.opsForList().range(RedisConstants.TYPE_LIST_KEY, 0L, size);
            return Result.ok(typeList);
        }
        // 2.查询type列表 将结果返回
        List<ShopType> typeList = query().orderByAsc("sort").list();
        redisTemplate.opsForList().leftPushAll(RedisConstants.TYPE_LIST_KEY,typeList);
        return Result.ok(typeList);
    }

    public static  List<String> getStringList(List<ShopType> list,Function<List<ShopType>,List<String>> function){
        return function.apply(list);
    }
}
