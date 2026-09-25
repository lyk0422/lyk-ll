package com.example.starter.port;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.starter.TestTimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * 并发与竞态测试：真实线程并发调用服务，验证唯一在途与唯一终态。
 */
@SpringBootTest
@Import(TestTimeConfig.class)
class PortConcurrencyTest extends PortTestSupport {

    /**
     * 两个并发申请同一号码：只能一个进入在途，另一个收到 IN_FLIGHT 冲突。
     */
    @Test
    void concurrentApplyAllowsOnlyOneInFlight() throws Exception {
        String msisdn = nextMsisdn();
        insertEligibleSubscriber(msisdn);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger inFlightConflict = new AtomicInteger();
            AtomicInteger unexpected = new AtomicInteger();

            Runnable attempt = () -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    service.apply(nextRequestId(), msisdn, "OP_A", "OP_B");
                    success.incrementAndGet();
                } catch (BusinessException e) {
                    if ("IN_FLIGHT".equals(e.code())) {
                        inFlightConflict.incrementAndGet();
                    } else {
                        unexpected.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                }
            };
            Future<?> first = pool.submit(attempt);
            Future<?> second = pool.submit(attempt);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);

            assertEquals(1, success.get(), "并发申请只能一个成功");
            assertEquals(1, inFlightConflict.get(), "另一个必须收到 IN_FLIGHT 冲突");
            assertEquals(0, unexpected.get(), "不允许出现其他异常");
            assertEquals(1, countFlows(msisdn));
            assertEquals(1, countInflight(msisdn));
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * 撤销与生效广播并发：形成唯一终态，不得出现已撤销又生效。
     */
    @Test
    void concurrentCancelAndBroadcastYieldSingleTerminalState() throws Exception {
        String msisdn = nextMsisdn();
        PortFlow flow = newConfirmedFlow(msisdn);
        clock.setInstant(Instant.parse("2026-09-26T03:00:00Z"));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger cancelWon = new AtomicInteger();
            AtomicInteger broadcastWon = new AtomicInteger();
            AtomicInteger unexpected = new AtomicInteger();

            Runnable cancelTask = () -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    service.cancel(flow.id(), "用户撤销");
                    cancelWon.incrementAndGet();
                } catch (BusinessException e) {
                    if (!List.of("ALREADY_EFFECTIVE", "STATE_RACE").contains(e.code())) {
                        unexpected.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                }
            };
            Runnable broadcastTask = () -> {
                ready.countDown();
                try {
                    go.await(5, TimeUnit.SECONDS);
                    service.broadcast(flow.id(), nextRequestId(), "CH1");
                    broadcastWon.incrementAndGet();
                } catch (BusinessException e) {
                    if (!List.of("INVALID_STATE", "STATE_RACE").contains(e.code())) {
                        unexpected.incrementAndGet();
                    }
                } catch (Exception e) {
                    unexpected.incrementAndGet();
                }
            };
            Future<?> cancelFuture = pool.submit(cancelTask);
            Future<?> broadcastFuture = pool.submit(broadcastTask);
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            cancelFuture.get(15, TimeUnit.SECONDS);
            broadcastFuture.get(15, TimeUnit.SECONDS);

            assertEquals(0, unexpected.get(), "不允许出现预期外异常");
            assertEquals(1, cancelWon.get() + broadcastWon.get(), "撤销与生效只能一个成功");

            PortStatus finalStatus = service.getFlow(flow.id()).status();
            int effectiveness = countEffectiveness(flow.id());
            if (broadcastWon.get() == 1) {
                assertEquals(PortStatus.EFFECTIVE, finalStatus);
                assertEquals(1, effectiveness);
            } else {
                assertEquals(PortStatus.CANCELLED, finalStatus);
                assertEquals(0, effectiveness);
            }
            assertEquals(0, countInflight(msisdn), "终态后在途名额必须释放");
        } finally {
            pool.shutdownNow();
        }
    }
}
