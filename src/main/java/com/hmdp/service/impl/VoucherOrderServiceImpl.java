package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private SeckillVoucherServiceImpl seckillVoucherService;
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> defaultRedisScript;
    static {
        defaultRedisScript = new DefaultRedisScript<>();
        defaultRedisScript.setLocation(new ClassPathResource("seckill.lua"));
        defaultRedisScript.setResultType(Long.class);
    }

    // 创建一个阻塞队列
    private BlockingQueue<VoucherOrder> blockingQueue = new ArrayBlockingQueue<>(1024*1024);
    // 创建一个线程池
    private static final ExecutorService ex = Executors.newSingleThreadExecutor();

    // 内部类  实现Runnable
    public class exemplent implements Runnable{
        @Override
        public void run() {
            while (true){
                try {
                    //VoucherOrder voucherOrder = blockingQueue.take();
                    // 实现创建订单
                   // handlerVoucherOrder(voucherOrder);
                    // 读取
                    List<MapRecord<String, Object, Object>> read = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.order", ReadOffset.lastConsumed()) // 读取最新的
                    );
                    if (read == null || read.isEmpty()){
                        continue;
                    }
                    MapRecord<String, Object, Object> entries = read.get(0);
                    Map<Object, Object> value = entries.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    handlerVoucherOrder(voucherOrder);
                    stringRedisTemplate.opsForStream().acknowledge("stream.order","g1",entries.getId());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        Long userId = voucherOrder.getId();
        RLock rLock = redissonClient.getLock("lock:order:"+userId);
        boolean b = rLock.tryLock();
        if(!b){
            return;
        }

        try {
            // 获取到上级的事务代理对象
            proxy.createVoucherResult(voucherOrder);
        } finally {
            rLock.unlock();
        }
    }

    @PostConstruct
    public void init(){
        ex.submit(new exemplent());
    }

    private  IVoucherOrderService proxy;

    @Override
    public Result seckillVoucher(Long voucherId) {
        // 订单id
        long order = new RedisWorker(stringRedisTemplate).getUUid("order");
        // 执行lua脚本
        Long execute = stringRedisTemplate.execute(defaultRedisScript
                , Collections.emptyList()
                , voucherId.toString(), UserHolder.getUser().getId(),order);
        // 判断lua的执行结果
        if(execute.intValue() != 0){
            return Result.fail(execute.intValue()==1?"库存不足":"不能重复下单");
        }
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        return Result.ok(order);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 执行lua脚本
        Long execute = stringRedisTemplate.execute(defaultRedisScript
                , Collections.emptyList()
                , voucherId.toString(), UserHolder.getUser().getId());
        // 判断lua的执行结果
        if(execute.intValue() != 0){
            return Result.fail(execute.intValue()==1?"库存不足":"不能重复下单");
        }
        // 放到阻塞线程中

        VoucherOrder voucherOrder = new VoucherOrder();
        long order = new RedisWorker(stringRedisTemplate).getUUid("order");
        voucherOrder.setId(order);
        voucherOrder.setVoucherId(voucherId);
        voucherOrder.setUserId(UserHolder.getUser().getId());
        blockingQueue.add(voucherOrder);
        proxy = (IVoucherOrderService)AopContext.currentProxy();
        return Result.ok(order);
    }*/
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1. 查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);

        // 2. 判断开始时间
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("活动还没有开始！");
        }
        // 3. 判断结束时间
        if (voucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("活动已经结束！");
        }
        // 4. 判断库存
        if(voucher.getStock() < 1){
            return Result.fail("活动已经结束！");
        }
        Long userId = UserHolder.getUser().getId();
        //SimpleRedisLock simpleRedisLock = new SimpleRedisLock(stringRedisTemplate,userId+"");

        RLock simpleRedisLock = redissonClient.getLock("lock:order:" + userId);
        boolean success = simpleRedisLock.tryLock();
        if (!success){
            return Result.fail("失败！");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            // 获取到上级的事务代理对象
            return proxy.createVoucherResult(voucherId);
        } finally {
            simpleRedisLock.unlock();
        }

    }*/

    @Transactional
    public void createVoucherResult(VoucherOrder voucherOrder) {
        // 5. 一人一单判断
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        if (count > 0){
            return;
        }
        // 5.1 扣减库存
        boolean update = seckillVoucherService.update().setSql("stock = stock -1").
                eq("voucher_id", voucherOrder.getVoucherId()).gt("stock", 0).update();
        if(!update){
            return;
        }
        // 6 创建订单
        save(voucherOrder);
        return;
    }
}
