package com.example.starter.epidemic;

import com.example.starter.epidemic.api.CardResponse;
import com.example.starter.epidemic.api.CreateInvestigationRequest;
import com.example.starter.epidemic.api.InvestigationResponse;
import com.example.starter.epidemic.service.ApiException;
import com.example.starter.epidemic.service.ClusterService;
import com.example.starter.epidemic.service.EpidemicCardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 聚集分析 H2 真实并发测试：同一事件并发生成协查单唯一成功；
 * 同场所并发报告由场所行锁串行化，只生成一个聚集事件。
 */
class ClusterConcurrencyTest extends AbstractIntegrationTest {

    private static final String SITE_ADDR = "示范市第一小学 3 班";

    @Autowired
    private EpidemicCardService cardService;

    @Autowired
    private ClusterService clusterService;

    /** 串行提交 3 例，构造一个聚集事件并返回事件编号。 */
    private long triggerEvent() {
        for (int i = 1; i <= 3; i++) {
            clock.setFixed(BASE_TIME.plusSeconds(i * 600L));
            cardService.submit(submitRequestAt("req-seed-" + i, "P-SEED-" + i, "B02",
                    BASE_TIME, SITE_ADDR));
        }
        return clusterService.listEvents().get(0).id();
    }

    @Test
    void concurrentInvestigationCreation_onlyOneSucceeds() throws Exception {
        long eventId = triggerEvent();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            AtomicInteger success = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        clusterService.createInvestigation(eventId,
                                new CreateInvestigationRequest("req-conc-inv-" + idx, "H002", "并发协查"));
                        success.incrementAndGet();
                    } catch (ApiException e) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> future : futures) {
                future.get(15, TimeUnit.SECONDS);
            }

            assertEquals(1, success.get(), "同一事件并发生成协查单只能唯一成功");
            assertEquals(1, rejected.get(), "落败方必须被业务拒绝且不产生协查单");
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM investigation_order WHERE event_id = ?",
                    Integer.class, eventId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cluster_event WHERE id = ? AND open_investigation_id IS NOT NULL",
                    Integer.class, eventId), "事件须登记唯一进行中协查单");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentReportsAtSameSite_singleClusterEvent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            CyclicBarrier barrier = new CyclicBarrier(3);
            List<Future<CardResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return cardService.submit(submitRequestAt("req-conc-rep-" + idx,
                            "P-CONC-REP-" + idx, "B02", BASE_TIME.minusSeconds(1800), SITE_ADDR));
                }));
            }
            List<String> cardNos = new ArrayList<>();
            for (Future<CardResponse> future : futures) {
                cardNos.add(future.get(15, TimeUnit.SECONDS).cardNo());
            }

            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM cluster_event", Integer.class),
                    "同场所并发 3 例只能生成一个聚集事件");
            long eventId = clusterService.listEvents().get(0).id();
            assertEquals(3, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM cluster_event_card WHERE event_id = ? AND arrival = 'NORMAL'",
                    Integer.class, eventId));
            List<String> timeline = clusterService.getEvent(eventId).cards().stream()
                    .map(c -> c.cardNo()).toList();
            assertTrue(timeline.containsAll(cardNos), "事件时间线须覆盖全部并发报告卡");
        } finally {
            pool.shutdownNow();
        }
    }
}
