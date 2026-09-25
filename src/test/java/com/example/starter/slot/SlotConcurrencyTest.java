package com.example.starter.slot;

import com.example.starter.slot.dto.ReturnResultView;
import com.example.starter.slot.dto.SlotSeriesRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发边界测试：真实并发线程 + 真实 H2 事务，验证唯一约束与原子释放。
 */
@SpringBootTest
class SlotConcurrencyTest {

    @Autowired
    private SlotService slotService;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService pool;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM slot_allocation");
        jdbc.update("DELETE FROM slot_request");
        jdbc.update("DELETE FROM slot_snapshot");
        jdbc.update("DELETE FROM slot_history");
        jdbc.update("DELETE FROM slot_publication");
        jdbc.update("INSERT INTO slot_publication(id, published_at) VALUES (1, NULL)");
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void shutdownPool() {
        pool.shutdownNow();
    }

    private int count(String sql) {
        Integer count = jdbc.queryForObject(sql, Integer.class);
        return count == null ? 0 : count;
    }

    @Test
    void concurrentApplySameSlotOnlyOneSucceeds() throws Exception {
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String requestId = "REQ-C-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    slotService.apply(new SlotSeriesRequest(requestId, "CA",
                            "CA" + requestId, "08:30", List.of(1)));
                    return "OK";
                } catch (SlotConflictException e) {
                    return "CONFLICT";
                }
            }));
        }
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();

        List<String> results = new ArrayList<>();
        for (Future<String> future : futures) {
            results.add(future.get(60, TimeUnit.SECONDS));
        }
        // 并发申请同一时刻同一周：只允许一家成功，其余得到明确冲突
        assertEquals(1, results.stream().filter("OK"::equals).count());
        assertEquals(threads - 1, results.stream().filter("CONFLICT"::equals).count());
        // 最终该时刻该周只有一条占用，失败者的系列整体回滚
        assertEquals(1, count("SELECT COUNT(*) FROM slot_allocation"
                + " WHERE slot_time='08:30' AND week_no=1"));
        assertEquals(1, count("SELECT COUNT(*) FROM slot_request"));
    }

    @Test
    void concurrentReturnAndApplyNeverDoubleAllocate() throws Exception {
        for (int round = 0; round < 5; round++) {
            jdbc.update("DELETE FROM slot_allocation");
            jdbc.update("DELETE FROM slot_request");
            String holderRequest = "REQ-A-" + round;
            String challengerRequest = "REQ-B-" + round;
            slotService.apply(new SlotSeriesRequest(holderRequest, "CA", "CA100",
                    "08:30", List.of(1)));

            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            Future<ReturnResultView> releaseFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                return slotService.release(holderRequest);
            });
            Future<String> applyFuture = pool.submit(() -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS));
                try {
                    slotService.apply(new SlotSeriesRequest(challengerRequest, "MU",
                            "MU200", "08:30", List.of(1)));
                    return "OK";
                } catch (SlotConflictException e) {
                    return "CONFLICT";
                }
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            ReturnResultView released = releaseFuture.get(60, TimeUnit.SECONDS);
            String applyResult = applyFuture.get(60, TimeUnit.SECONDS);

            // 退回必须成功，原持有方不再占用
            assertEquals(1, released.released().size());
            assertEquals(0, count("SELECT COUNT(*) FROM slot_allocation WHERE request_id='"
                    + holderRequest + "'"));
            // 终态原子：该时刻该周要么被新申请方唯一持有，要么空闲，绝不同时归属两家
            int holders = count("SELECT COUNT(*) FROM slot_allocation"
                    + " WHERE slot_time='08:30' AND week_no=1");
            if ("OK".equals(applyResult)) {
                assertEquals(1, holders);
            } else {
                assertEquals("CONFLICT", applyResult);
                assertEquals(0, holders);
            }
        }
    }
}
