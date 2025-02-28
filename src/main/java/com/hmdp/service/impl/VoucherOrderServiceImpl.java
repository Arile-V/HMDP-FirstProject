package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.hmdp.utils.RedisIdWorker;

import com.hmdp.utils.UserHolder;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
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
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource private final StringRedisTemplate stringRedisTemplate;
    @Resource private ISeckillVoucherService seckillVoucherService;
    @Resource private RedisIdWorker redisIdWorker;
    public VoucherOrderServiceImpl(StringRedisTemplate stringRedisTemplate,
                                   ISeckillVoucherService seckillVoucherService ,
                                   RedisIdWorker redisIdWorker) {
        this.seckillVoucherService = seckillVoucherService;
        this.redisIdWorker = redisIdWorker;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    private ExecutorService executorService = Executors.newFixedThreadPool(10);
    @PostConstruct
    public void init() {
        executorService.submit(new VoucherOrderTask());
    }//启动线程专门用来实现异步写库
    String queueName = "stream.orders";
    private class VoucherOrderTask implements Runnable {
        @SneakyThrows
        @Override
        public void run() {//！！！异步写库线程（基于Redis的PubSub模式）
//            while (true) {
////                try {
////                    VoucherOrder voucherOrd = blockQueue.take();
////                    handelOrder(voucherOrd);
////                }catch (Exception e){
////                    log.debug("error:{}",e.getMessage());
////                }
//                try {
//                    //log.debug("h1");
//                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
//                            Consumer.from("g1", "c1"),
//                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
//                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
//                    );
//                    if (list==null) {
//                        log.debug("h2:{}",list);
//                        continue;
//                    }
//                    if (list.isEmpty()){
//                        continue;
//                    }
//                    MapRecord<String,Object,Object> mapRecord = list.get(0);
//                    Map<Object,Object> value = mapRecord.getValue();
//                    VoucherOrder order = BeanUtil.fillBeanWithMap(value,new VoucherOrder(),true);
//                    handelOrder(order);
//                    log.debug("h3");
//                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",mapRecord.getId());
//                }catch(Exception e){
//                    log.debug("处理订单异常",e);
//                    handlePendingList();
//                }
//            }
        }

    }

    @RabbitListener(queues = "seckill")
    @Transactional
    public void listen2Queue(String message) {//监听队列来写库
        log.debug("收到！{}",message);//收到的是订单ID
        //起一个代理对象写库
        //createVoucherOrder()
        //handelOrder();
        VoucherOrder order = JSONUtil.toBean(message, VoucherOrder.class);
        //handelOrder(order);
        createVoucherOrder(order);
    }

    private void handlePendingList() {
        while (true) {
            try {
                List<MapRecord<String,Object,Object>> list = stringRedisTemplate
                        .opsForStream().read(Consumer.from("g1","c1"),
                                StreamReadOptions.empty().block(Duration.ofSeconds(2)).count(1),
                                StreamOffset.create(queueName, ReadOffset.from("0")));
                if (list==null||list.isEmpty()||list.size()==0) {
                    break;
                }
                MapRecord<String,Object,Object> mapRecord = list.get(0);
                Map<Object,Object> value = mapRecord.getValue();
                VoucherOrder order = BeanUtil.fillBeanWithMap(value,new VoucherOrder(),true);
                handelOrder(order);
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",mapRecord.getId());
            } catch (Exception e) {
                log.debug("处理Pending-list异常",e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }


    public IVoucherOrderService proxy ;
    private void handelOrder(VoucherOrder voucherOrder) {
        //1.获取用户
        Long userId = voucherOrder.getUserId();
        //2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:"+userId);
        //3.获取锁
        boolean isLock = lock.tryLock();
        //4.判断是否获取锁成功
        if(!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            //获取代理对象
            proxy.createVoucherOrder(voucherOrder);
        }finally {
            lock.unlock();
        }
    }

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static{
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource private RedissonClient redissonClient;
    @Resource private RabbitTemplate rabbitTemplate;
    //private ArrayBlockingQueue<VoucherOrder> blockQueue = new ArrayBlockingQueue<>(1024*1024);
    @Override
    public Result OrderVoucher(Long voucherId) {
//        long orderId = redisIdWorker.nextId("order");
//        Long result = stringRedisTemplate.execute( //调用execute方法，返回值
//                SECKILL_SCRIPT, //加载的模板对象
//                Collections.emptyList(),    //键参数
//                voucherId.toString(),    //值参数1
//                UserHolder.getUser().getId().toString() ,
//                String.valueOf(orderId)//值参数2
//        );
//        int r = result.intValue();
//        if (r != 0){
//
//            return Result.fail(r==1 ? "库存不足":"不能重复下单");
//        }
//        // 保存阻塞队列,异步写SQL
//        proxy = (IVoucherOrderService) AopContext.currentProxy();
////            //创建订单
////            VoucherOrder voucherOrder = new VoucherOrder();
////            //订单
////            voucherOrder.setId(orderId);
////            voucherOrder.setVoucherId(voucherId);
////            voucherOrder.setUserId(UserHolder.getUser().getId());
////            blockQueue.add(voucherOrder);
////            proxy = (IVoucherOrderService) AopContext.currentProxy();//初始化代理对象，开始处理阻塞队列当中的对象
//        return Result.ok(orderId);

        //获取订单id
        long orderId = redisIdWorker.nextId("order");
        //1.执行Lua脚本(判断用户是否有购买资格，消息发出)
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                UserHolder.getUser().getId().toString(),
                String.valueOf(orderId)
        );
        // TODO 用rabbitMQ重写异步写库操作
        //创建订单
            VoucherOrder values = new VoucherOrder();
            //订单
            values.setId(orderId);
            values.setVoucherId(voucherId);
            values.setUserId(UserHolder.getUser().getId());
            String msg = JSONUtil.toJsonStr(values);
        Long MsgId = redisIdWorker.nextId("order");
        Message message = MessageBuilder
                .withBody(msg.getBytes())
                .setMessageId(MsgId.toString())
                .setExpiration("50000")//TTL
                .build();
        rabbitTemplate.convertAndSend("seckill",msg);//Lua脚本当中发送的是用户id，优惠券id，订单id

        int r = result.intValue();
        if(r != 0){ //2.判断结果是否为0,不为0,代表没有购买资格
            return Result.fail(r==1 ? "库存不足":"不能重复下单");
        }
        //获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //3.返回订单id
        return Result.ok(orderId);

    }
//    @Override
//    public Result OrderVoucher(Long voucherId) {
//        //查询
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //判断是否在时间段内
//        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())||seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
//            return Result.fail("不在活动时间段内");
//        }
//        //判断库存是否充足
//        if (seckillVoucher.getStock()<=0){
//            return Result.fail("券已经售空！");
//        }
//        Long userId = UserHolder.getUser().getId();
//        //SimpleRedisLock lock = new SimpleRedisLock("order:"+userId,stringRedisTemplate);
//
//        RLock lock = redissonClient.getLock("lock:order:"+userId);
//
//        boolean isLock = lock.tryLock();
//        log.debug("isLock:{}",isLock);
//        //判断是否获取锁成功
//        if(!isLock) {
//            log.debug("fail");
//            return Result.fail("不允许重复下单");
//        }
//        try {//用悲观锁做用户上锁
//            IVoucherOrderService io = (IVoucherOrderService) AopContext.currentProxy();//制作一个代理对象，使得事务不会因为自调用的原因失效
//            return io.tryNewOrder(voucherId);
//        }finally {
//            lock.unlock();
//        }
//    }

    @Override
    @Transactional
    public Result tryNewOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();


            Integer haveBill = query()
                    .eq("voucher_id", voucherId)
                    .eq("user_id",userId)
                    .count();
            if (haveBill>0){
                return Result.fail("购买数量达到上限");
            }
            //扣减库存
//        Boolean isSuccess = seckillVoucherService.update()
//                .setSql("stock = stock - 1")
//                .eq("voucher_id",voucherId)
//                .eq("stock",seckillVoucher.getStock()) //这样使用CAS法去做乐观锁会导致成功率大大降低，在非最后一个的关键节点也会出现失败
//                .update();
            boolean isSuccess = seckillVoucherService.update()
                    .setSql("stock = stock - 1")
                    .eq("voucher_id", voucherId)
                    .gt("stock",0)//这样就只会在关键时刻做乐观锁的判断，非关键节点不会失败
                    .update();
//        seckillVoucher.setStock(seckillVoucher.getStock() - 1);
//        seckillVoucherService.updateById(seckillVoucher);
            if (!isSuccess){
                return Result.fail("交易失败！库存不足");
            }
            //创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单
            Long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setUserId(userId);
            save(voucherOrder);
            //返回订单id
            return Result.ok(orderId);

    }

    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //6.一人一单
        Long userId = voucherOrder.getUserId();
        //6.1查询订单
        int count = query().eq("user_id", userId).eq("voucher_id", voucherOrder.getVoucherId()).count();
        //6.2判断是否存在
        if(count>0){
            //用户已经购买过了
            log.error("用户已经购买过一次！");
            return;
        }
        //3.2库存充足扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1") //相当于set条件 set stock = stock - 1
                .eq("voucher_id", voucherOrder.getVoucherId()) //相当于where条件 where id = ? and stock = ?
                .gt("stock",0).update();
        if(!success){
            log.error("库存不足!");
            return;
        }

        long orderId = redisIdWorker.nextId("order");//订单id
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherOrder.getVoucherId());//代金券id
        save(voucherOrder);

    }


}
