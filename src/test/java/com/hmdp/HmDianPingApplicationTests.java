package com.hmdp;

import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;

@Slf4j
@SpringBootTest
class HmDianPingApplicationTests {
//    @Resource
//    private ShopServiceImpl shopService;
//    @Resource
//    private RedisIdWorker redisIdWorker;
//    private final ExecutorService es = Executors.newFixedThreadPool(500);
//    @Test
//    void updateStatus() throws InterruptedException {
//        shopService.setHotData(1L,30L);
//    }
//    @Test
//    void testIdWorker() throws InterruptedException {
//        CountDownLatch latch = new CountDownLatch(300);
//        Runnable task = ()->{
//            for(int i=0;i<100;i++){
//                long id = redisIdWorker.nextId("order");
//                System.out.println("id="+id);
//            }
//            latch.countDown();
//        };
//        long begin = System.currentTimeMillis();
//        for(int i=0;i<300;i++){
//            es.submit(task);
//        }
//        latch.await();
//        long end = System.currentTimeMillis();
//        System.out.println("Result Time = " + (end-begin));
//    }

}
