package com.example.starter;

import com.example.starter.model.AcceptanceRole;
import com.example.starter.service.ApiException;
import com.example.starter.web.dto.BoardingRequest;
import com.example.starter.web.dto.MaintenanceAcceptanceRequest;
import com.example.starter.web.dto.MaintenanceClearanceRequest;
import com.example.starter.web.dto.MaintenanceOrderCreateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 检修并发门禁测试：生效检修与上客、验收与上客并发时形成一致次序，
 * 不得出现有效封锁与新上客同时成功；同键并发只执行一次。
 */
class MaintenanceConcurrencyTest extends BaseIntegrationTest {

    private static final Instant START = T0.plusSeconds(3600);
    private static final Instant END = T0.plusSeconds(3 * 3600);

    @Test
    void concurrentBoardingAllRejectedWhileMaintenanceEffective() throws Exception {
        long orderId = createOrder(List.of(SEGMENT_1), START, END);
        timeProvider.useFixedClock(START.plusSeconds(60));

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    controlEventService.board(LINE_ID,
                            new BoardingRequest(UUID.randomUUID().toString(), 1));
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        for (Future<Integer> future : futures) {
            assertThat(future.get(30, TimeUnit.SECONDS)).isEqualTo(409);
        }
        pool.shutdown();
        assertThat(countRows("boarding")).isZero();
        assertThat(maintenanceService.orderView(orderId).blocked()).isTrue();
    }

    @Test
    void concurrentBoardingAndAcceptanceFormConsistentOrder() throws Exception {
        long orderId = createOrder(List.of(SEGMENT_1), START, END);
        timeProvider.useFixedClock(START);
        clearSegment(orderId, 1, SEGMENT_1, "op-clear");
        timeProvider.useFixedClock(END);
        accept(orderId, 2, AcceptanceRole.MECHANICAL, "mt-1");

        // 最后一岗验收（解除阻断）与并发上客竞争：结果必须形成一致次序
        int boardThreads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(boardThreads + 1);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> acceptance = pool.submit(() -> {
            gate.await();
            return maintenanceService.accept(orderId, new MaintenanceAcceptanceRequest(
                    UUID.randomUUID().toString(), 3, AcceptanceRole.INSPECTION, "insp-1", "验收合格"))
                    .getStatusCode().value();
        });
        List<Future<Integer>> boardings = new ArrayList<>();
        for (int i = 0; i < boardThreads; i++) {
            boardings.add(pool.submit(() -> {
                gate.await();
                try {
                    controlEventService.board(LINE_ID,
                            new BoardingRequest(UUID.randomUUID().toString(), 1));
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        assertThat(acceptance.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        int succeeded = 0;
        for (Future<Integer> boarding : boardings) {
            int status = boarding.get(30, TimeUnit.SECONDS);
            assertThat(status).isIn(200, 409);
            if (status == 200) {
                succeeded++;
            }
        }
        pool.shutdown();
        // 一致次序：成功的上客数与落库行数一致，不存在"有效封锁却上客成功"的中间态
        assertThat(countRows("boarding")).isEqualTo(succeeded);
        assertThat(maintenanceService.orderView(orderId).status()).isEqualTo("COMPLETED");
    }

    @Test
    void concurrentBoardingAndOrderActivationFormConsistentOrder() throws Exception {
        // 时钟固定在窗口起点：登记即生效，与并发上客竞争
        timeProvider.useFixedClock(START);
        int boardThreads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(boardThreads + 1);
        CountDownLatch gate = new CountDownLatch(1);
        Future<Integer> create = pool.submit(() -> {
            gate.await();
            return maintenanceService.createOrder(LINE_ID, new MaintenanceOrderCreateRequest(
                    UUID.randomUUID().toString(), List.of(SEGMENT_1), START, END))
                    .getStatusCode().value();
        });
        List<Future<Integer>> boardings = new ArrayList<>();
        for (int i = 0; i < boardThreads; i++) {
            boardings.add(pool.submit(() -> {
                gate.await();
                try {
                    controlEventService.board(LINE_ID,
                            new BoardingRequest(UUID.randomUUID().toString(), 1));
                    return 200;
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        assertThat(create.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        int succeeded = 0;
        for (Future<Integer> boarding : boardings) {
            int status = boarding.get(30, TimeUnit.SECONDS);
            assertThat(status).isIn(200, 409);
            if (status == 200) {
                succeeded++;
            }
        }
        pool.shutdown();
        assertThat(countRows("boarding")).isEqualTo(succeeded);
        // 登记生效后，后续上客一律拒绝
        timeProvider.useFixedClock(START.plusSeconds(60));
        try {
            controlEventService.board(LINE_ID, new BoardingRequest(UUID.randomUUID().toString(), 1));
        } catch (ApiException e) {
            assertThat(e.status().value()).isEqualTo(409);
        }
        assertThat(countRows("boarding")).isEqualTo(succeeded);
    }

    @Test
    void concurrentCreateSameRequestIdExecutesOnce() throws Exception {
        String requestId = UUID.randomUUID().toString();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<ResponseEntity<String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                gate.await();
                return maintenanceService.createOrder(LINE_ID, new MaintenanceOrderCreateRequest(
                        requestId, List.of(SEGMENT_1, SEGMENT_2), START, END));
            }));
        }
        gate.countDown();
        List<String> bodies = new ArrayList<>();
        for (Future<ResponseEntity<String>> future : futures) {
            ResponseEntity<String> response = future.get(30, TimeUnit.SECONDS);
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            bodies.add(response.getBody());
        }
        pool.shutdown();
        assertThat(bodies).allMatch(body -> body.equals(bodies.get(0)));
        assertThat(countRows("maintenance_order")).isEqualTo(1);
        assertThat(countRows("idempotency_record")).isEqualTo(1);
    }

    @Test
    void concurrentSameRoleAcceptanceOnlyOneSucceeds() throws Exception {
        long orderId = createOrder(List.of(SEGMENT_1), START, END);
        timeProvider.useFixedClock(START);
        clearSegment(orderId, 1, SEGMENT_1, "op-clear");
        timeProvider.useFixedClock(END);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String operator = "mt-" + i;
            futures.add(pool.submit(() -> {
                gate.await();
                try {
                    return maintenanceService.accept(orderId, new MaintenanceAcceptanceRequest(
                            UUID.randomUUID().toString(), 2, AcceptanceRole.MECHANICAL, operator, "验收合格"))
                            .getStatusCode().value();
                } catch (ApiException e) {
                    return e.status().value();
                }
            }));
        }
        gate.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        // 同岗并发验收：恰好一个成功，另一个因版本或岗位冲突失败
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(countRows("maintenance_acceptance")).isEqualTo(1);
        assertThat(maintenanceService.orderView(orderId).version()).isEqualTo(3);
    }

    private long createOrder(List<Long> segmentIds, Instant start, Instant end) {
        ResponseEntity<String> response = maintenanceService.createOrder(LINE_ID,
                new MaintenanceOrderCreateRequest(UUID.randomUUID().toString(), segmentIds, start, end));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return readJson(response.getBody()).get("id").asLong();
    }

    private void clearSegment(long orderId, int expectedVersion, long segmentId, String operatorId) {
        ResponseEntity<String> response = maintenanceService.recordClearance(orderId,
                new MaintenanceClearanceRequest(UUID.randomUUID().toString(), expectedVersion,
                        segmentId, operatorId, "已疏散"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }

    private void accept(long orderId, int expectedVersion, AcceptanceRole role, String operatorId) {
        ResponseEntity<String> response = maintenanceService.accept(orderId,
                new MaintenanceAcceptanceRequest(UUID.randomUUID().toString(), expectedVersion,
                        role, operatorId, "验收合格"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
    }
}
