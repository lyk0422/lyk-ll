package com.example.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.starter.dto.CreateDefectRequest;
import com.example.starter.dto.CreateRepairOrderRequest;
import com.example.starter.dto.DefectResponse;
import com.example.starter.dto.RepairOrderView;
import com.example.starter.error.ApiException;
import com.example.starter.model.DefectLevel;
import com.example.starter.service.DefectService;
import com.example.starter.service.RepairService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

/**
 * 并发测试：同一叶片同时刻只允许一张进行中维修单（数据库唯一约束保证）。
 */
class RepairOrderConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private DefectService defectService;

    @Autowired
    private RepairService repairService;

    @Test
    void concurrentRepairOrderCreationOnSameBladeAllowsOnlyOne() throws Exception {
        // 同叶片两条结构级缺陷
        DefectResponse defectA = defectService.register(new CreateDefectRequest(
                UUID.randomUUID().toString(), 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null));
        DefectResponse defectB = defectService.register(new CreateDefectRequest(
                UUID.randomUUID().toString(), 1L, "叶尖", DefectLevel.STRUCTURAL, "CRACK", null));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<RepairOrderView> successes = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<ApiException> conflicts = new ConcurrentLinkedQueue<>();
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> createOrder(ready, start, defectA.id(), successes, conflicts)),
                    pool.submit(() -> createOrder(ready, start, defectB.id(), successes, conflicts)));
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        // 恰好一单成功，另一单 409
        assertThat(successes).hasSize(1);
        assertThat(conflicts).hasSize(1);
        assertThat(conflicts.peek().status()).isEqualTo(HttpStatus.CONFLICT);

        // 数据库最终状态：仅一张进行中维修单
        Integer inProgress = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_order WHERE blade_id = 1 AND status = 'IN_PROGRESS'",
                Integer.class);
        assertThat(inProgress).isEqualTo(1);
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM repair_order WHERE blade_id = 1", Integer.class);
        assertThat(total).isEqualTo(1);
    }

    private void createOrder(CountDownLatch ready, CountDownLatch start, long defectId,
                             ConcurrentLinkedQueue<RepairOrderView> successes,
                             ConcurrentLinkedQueue<ApiException> conflicts) {
        try {
            ready.countDown();
            start.await(5, TimeUnit.SECONDS);
            successes.add(repairService.createOrder(new CreateRepairOrderRequest(
                    UUID.randomUUID().toString(), defectId)));
        } catch (ApiException e) {
            conflicts.add(e);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void secondOrderAllowedAfterFirstClosed() {
        DefectResponse defectA = defectService.register(new CreateDefectRequest(
                UUID.randomUUID().toString(), 1L, "叶根", DefectLevel.STRUCTURAL, "CRACK", null));
        RepairOrderView orderA = repairService.createOrder(new CreateRepairOrderRequest(
                UUID.randomUUID().toString(), defectA.id()));

        // 第一张单进行中时，第二张单 409
        DefectResponse defectB = defectService.register(new CreateDefectRequest(
                UUID.randomUUID().toString(), 1L, "叶尖", DefectLevel.STRUCTURAL, "CRACK", null));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repairService.createOrder(
                new CreateRepairOrderRequest(UUID.randomUUID().toString(), defectB.id())))
                .isInstanceOfSatisfying(ApiException.class, e ->
                        assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));

        // 第一张单闭环后，第二张单可创建
        repairService.reinspect(orderA.id(), new com.example.starter.dto.CreateReinspectionRequest(
                UUID.randomUUID().toString(), com.example.starter.model.ReinspectionResult.PASS));
        RepairOrderView orderB = repairService.createOrder(new CreateRepairOrderRequest(
                UUID.randomUUID().toString(), defectB.id()));
        assertThat(orderB.status()).isEqualTo("IN_PROGRESS");
    }
}
