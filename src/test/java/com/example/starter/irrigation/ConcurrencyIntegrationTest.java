package com.example.starter.irrigation;

import com.example.starter.irrigation.dto.GroupContracts.CreateGroupRequest;
import com.example.starter.irrigation.dto.GroupContracts.GroupView;
import com.example.starter.irrigation.dto.GroupContracts.OutletInput;
import com.example.starter.irrigation.dto.MeteringContracts.MeteringRequest;
import com.example.starter.irrigation.dto.PlanContracts.PlanView;
import com.example.starter.irrigation.dto.PlanContracts.PublishPlanRequest;
import com.example.starter.irrigation.dto.SettlementContracts.SealRequest;
import com.example.starter.irrigation.dto.SettlementContracts.SettlementView;
import com.example.starter.irrigation.error.BizException;
import com.example.starter.irrigation.repo.PlanRepository;
import com.example.starter.irrigation.service.GroupService;
import com.example.starter.irrigation.service.MeteringService;
import com.example.starter.irrigation.service.PlanService;
import com.example.starter.irrigation.service.SettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;

/**
 * 并发与幂等边界测试：真实并发执行，协调栅栏与超时，断言最终结果与数据库状态。
 */
class ConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final int THREADS = 8;

    @MockitoSpyBean
    private PlanRepository planRepository;

    @org.springframework.beans.factory.annotation.Autowired
    private GroupService groupService;

    @org.springframework.beans.factory.annotation.Autowired
    private PlanService planService;

    @org.springframework.beans.factory.annotation.Autowired
    private MeteringService meteringService;

    @org.springframework.beans.factory.annotation.Autowired
    private SettlementService settlementService;

    private GroupView createStandardGroup() {
        return groupService.create(new CreateGroupRequest("GRP-C", "并发测试组", List.of(
                new OutletInput("D1", "斗口1", new BigDecimal("100")),
                new OutletInput("D2", "斗口2", new BigDecimal("200")),
                new OutletInput("D3", "斗口3", new BigDecimal("300")))));
    }

    private long outletId(GroupView group, String code) {
        return group.outlets().stream().filter(o -> o.code().equals(code)).findFirst().orElseThrow().outletId();
    }

    private static void awaitStart(CountDownLatch start) {
        try {
            assertTrue(start.await(15, TimeUnit.SECONDS), "并发栅栏等待超时");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发栅栏被中断", e);
        }
    }

    @Test
    void 并发发布同轮同组_唯一成功_其余409() throws Exception {
        GroupView group = createStandardGroup();
        long groupId = group.groupId();

        // 栅栏：强制全部发布事务在读取版本号前汇合，构造真实并发窗口。
        CountDownLatch arrived = new CountDownLatch(THREADS);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            arrived.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS), "并发栅栏等待超时");
            return result;
        }).when(planRepository).findMaxVersionNo(anyLong(), anyInt());

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            String requestId = "req-concurrent-" + i;
            futures.add(pool.submit(() -> {
                try {
                    planService.publish(new PublishPlanRequest(requestId, groupId, 1, 10000L));
                    success.incrementAndGet();
                } catch (BizException e) {
                    assertEquals(HttpStatus.CONFLICT, e.status());
                    conflict.incrementAndGet();
                }
            }));
        }
        assertTrue(arrived.await(15, TimeUnit.SECONDS), "并发线程未全部就位");
        release.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, success.get(), "并发发布应唯一成功");
        assertEquals(THREADS - 1, conflict.get(), "其余并发发布应 409");

        // 失败事务已回滚：只存在一个版本，且为唯一生效版本。
        List<PlanView> versions = planService.listVersions(groupId, 1);
        assertEquals(1, versions.size());
        assertEquals(PlanService.STATUS_EFFECTIVE, versions.get(0).status());
        assertEquals(1, versions.get(0).versionNo());
        Integer pointerCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM round_effective_plan WHERE group_id = ? AND round_no = 1",
                Integer.class, groupId);
        assertEquals(1, pointerCount);
    }

    @Test
    void 并发封存_仅一个结算_重复封存幂等成功() throws Exception {
        GroupView group = createStandardGroup();
        long groupId = group.groupId();
        planService.publish(new PublishPlanRequest("req-seal-plan", groupId, 1, 10000L));
        meteringService.report(new MeteringRequest(outletId(group, "D1"), 1, 500L));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SettlementView>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                assertTrue(start.await(15, TimeUnit.SECONDS));
                return settlementService.seal(new SealRequest(groupId, 1));
            }));
        }
        start.countDown();
        List<SettlementView> results = new CopyOnWriteArrayList<>();
        for (Future<SettlementView> future : futures) {
            results.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 全部成功且指向同一结算，仅首次非重放。
        long distinctIds = results.stream().map(SettlementView::settlementId).distinct().count();
        assertEquals(1, distinctIds);
        assertEquals(1, results.stream().filter(r -> !r.replayed()).count());
        Integer settlements = jdbc.queryForObject(
                "SELECT COUNT(*) FROM round_settlement WHERE group_id = ? AND round_no = 1",
                Integer.class, groupId);
        assertEquals(1, settlements);
        Integer details = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_detail d JOIN round_settlement s ON d.settlement_id = s.id"
                        + " WHERE s.group_id = ? AND s.round_no = 1",
                Integer.class, groupId);
        assertEquals(3, details);
    }

    @Test
    void 并发计量同斗口同轮次_去重不重复累计() throws Exception {
        GroupView group = createStandardGroup();
        long groupId = group.groupId();
        long outletId = outletId(group, "D1");
        planService.publish(new PublishPlanRequest("req-meter-plan", groupId, 1, 10000L));

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            futures.add(pool.submit(() -> {
                awaitStart(start);
                var view = meteringService.report(new MeteringRequest(outletId, 1, 500L));
                if ("CONFIRMED".equals(view.status())) {
                    confirmed.incrementAndGet();
                }
            }));
        }
        start.countDown();
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(THREADS, confirmed.get());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM metering WHERE outlet_id = ? AND round_no = 1",
                Integer.class, outletId);
        Long amount = jdbc.queryForObject(
                "SELECT amount_m3 FROM metering WHERE outlet_id = ? AND round_no = 1",
                Long.class, outletId);
        assertEquals(1, rows, "同斗口同轮次只允许一行计量");
        assertEquals(500L, amount, "重复上报不得重复累计");
    }

    @Test
    void 计量与封存并发_结转以最后已确认快照为准_结果稳定可复算() throws Exception {
        GroupView group = createStandardGroup();
        long groupId = group.groupId();
        long outletId = outletId(group, "D2");
        planService.publish(new PublishPlanRequest("req-race-plan", groupId, 1, 10000L));

        int meteringThreads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(meteringThreads + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger late = new AtomicInteger();
        List<Future<?>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < meteringThreads; i++) {
            futures.add(pool.submit(() -> {
                awaitStart(start);
                var view = meteringService.report(new MeteringRequest(outletId, 1, 3000L));
                if ("CONFIRMED".equals(view.status())) {
                    confirmed.incrementAndGet();
                } else {
                    late.incrementAndGet();
                }
            }));
        }
        Future<SettlementView> sealFuture = pool.submit(() -> {
            assertTrue(start.await(15, TimeUnit.SECONDS));
            return settlementService.seal(new SealRequest(groupId, 1));
        });
        start.countDown();
        SettlementView settlement = sealFuture.get(30, TimeUnit.SECONDS);
        for (Future<?> future : futures) {
            future.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(meteringThreads, confirmed.get() + late.get());
        // 封存后迟到计量只记事件：迟到数等于事件表行数。
        Integer lateEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM late_metering_event WHERE group_id = ? AND round_no = 1",
                Integer.class, groupId);
        assertEquals(late.get(), lateEvents);
        // 计量行只在有已确认上报时存在，且快照不重复累计。
        Integer meteringRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM metering WHERE outlet_id = ? AND round_no = 1",
                Integer.class, outletId);
        assertEquals(confirmed.get() > 0 ? 1 : 0, meteringRows);
        // 结转以封存前最后一次已确认快照为准：有确认则实供 3000，否则 0。
        var d2 = settlement.details().stream()
                .filter(d -> d.outletId() == outletId).findFirst().orElseThrow();
        long expectedActual = confirmed.get() > 0 ? 3000L : 0L;
        assertEquals(expectedActual, d2.actualM3());
        assertEquals(3333L - expectedActual, d2.deficitM3());

        // 结果稳定可复算：重复封存返回完全一致的首次结果。
        SettlementView replay = settlementService.seal(new SealRequest(groupId, 1));
        assertTrue(replay.replayed());
        assertEquals(settlement.settlementId(), replay.settlementId());
        assertEquals(settlement.details(), replay.details());
    }
}
