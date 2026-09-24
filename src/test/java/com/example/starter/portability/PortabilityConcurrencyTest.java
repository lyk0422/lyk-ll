package com.example.starter.portability;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.example.starter.portability.dto.ApplyRequest;
import com.example.starter.portability.dto.RequestIdRequest;
import com.example.starter.portability.dto.StoredResponse;
import com.example.starter.portability.support.AbstractPortabilityTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发边界测试：并发申请同一号码仅一个在途；撤销与生效并发形成唯一终态。
 */
class PortabilityConcurrencyTest extends AbstractPortabilityTest {

    private static final Instant WINDOW_TIME = Instant.parse("2026-09-25T02:30:00Z");

    @Autowired
    private PortabilityService service;

    @Test
    @DisplayName("并发申请同一号码：仅一个进入在途，其余全部 409")
    void concurrentApplyAllowsSingleInFlight() throws Exception {
        insertProfile("13900000101", false, false, null);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<StoredResponse>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return service.apply(new ApplyRequest("req-conc-" + index, "13900000101"));
            }));
        }
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<StoredResponse> future : futures) {
            statuses.add(future.get(15, TimeUnit.SECONDS).httpStatus());
        }
        pool.shutdownNow();

        assertThat(statuses.stream().filter(s -> s == 200).count()).isEqualTo(1);
        assertThat(statuses.stream().filter(s -> s == 409).count()).isEqualTo(threads - 1);

        Integer activeFlows = jdbc.sql("SELECT COUNT(*) FROM portability_flow "
                        + "WHERE msisdn = '13900000101' AND active_key IS NOT NULL")
                .query(Integer.class)
                .single();
        assertThat(activeFlows).isEqualTo(1);
        Integer appliedEvents = jdbc.sql("SELECT COUNT(*) FROM portability_event WHERE event_type = 'APPLIED'")
                .query(Integer.class)
                .single();
        assertThat(appliedEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("撤销与生效并发：形成唯一终态，不得出现已撤销又生效")
    void concurrentCancelAndBroadcastYieldSingleTerminalState() throws Exception {
        insertProfile("13900000102", false, false, null);
        StoredResponse apply = service.apply(new ApplyRequest("req-race-apply", "13900000102"));
        long flowId = parseJson(apply.body()).get("flowId").asLong();
        service.issueAuthCode(flowId, new RequestIdRequest("req-race-code"));
        service.donorConfirm(flowId, new RequestIdRequest("req-race-confirm"));
        clock.setInstant(WINDOW_TIME);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<StoredResponse> cancelFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return service.cancel(flowId, new RequestIdRequest("req-race-cancel"));
        });
        Future<StoredResponse> broadcastFuture = pool.submit(() -> {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            return service.broadcast(flowId, new RequestIdRequest("req-race-broadcast"));
        });
        assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        StoredResponse cancel = cancelFuture.get(15, TimeUnit.SECONDS);
        StoredResponse broadcast = broadcastFuture.get(15, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 一个成功一个冲突：终态唯一
        assertThat(List.of(cancel.httpStatus(), broadcast.httpStatus()))
                .containsExactlyInAnyOrder(200, 409);

        String finalStatus = flowStatus(flowId);
        assertThat(finalStatus).isIn("ACTIVATED", "CANCELLED");
        int activatedEvents = countEvents(flowId, "ACTIVATED");
        int cancelledEvents = countEvents(flowId, "CANCELLED");
        assertThat(activatedEvents + cancelledEvents).isEqualTo(1);
        if ("ACTIVATED".equals(finalStatus)) {
            assertThat(activatedEvents).isEqualTo(1);
            assertThat(broadcast.httpStatus()).isEqualTo(200);
        } else {
            assertThat(cancelledEvents).isEqualTo(1);
            assertThat(cancel.httpStatus()).isEqualTo(200);
        }
    }
}
