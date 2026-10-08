package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 召回竞争：召回与投放生效、召回与最后解除在事务内重查批号状态，
 * 不得把被召回药剂扣库成功，也不得以无效投放解除。
 * 两种竞争时序分别断言最终一致状态，而非依赖固定时序。
 */
class RecallConcurrencyTest extends BaseIntegrationTest {

    @Test
    void recallRacesWithPlacementConfirm() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacementWithBatch(caseId, "pl-1", 1L, 1L, 10, "张三");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Integer> confirmFuture = pool.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            MvcResult result = mockMvc.perform(MockMvcRequestBuilders
                    .post("/api/placements/" + placementId + "/confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("requestId", "pc-1",
                            "registrar", "李四", "pesticideId", 1, "dosage", 10,
                            "point", "3排-2架"))))
                    .andReturn();
            return result.getResponse().getStatus();
        });
        Future<Integer> recallFuture = pool.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/batches/1/recall")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("requestId", "rc-1",
                            "reason", "质量抽检不合格", "operator", "质管员", "expectedVersion", 1))))
                    .andReturn();
            return result.getResponse().getStatus();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        int confirmStatus = confirmFuture.get(30, TimeUnit.SECONDS);
        int recallStatus = recallFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        // 召回本身不受投放竞争影响，必然生效
        assertEquals(200, recallStatus);
        assertEquals("RECALLED", jdbc.queryForObject(
                "SELECT status FROM pesticide_batch WHERE id = 1", String.class));

        String placementStatus = jdbc.queryForObject(
                "SELECT status FROM treatment_placement WHERE id = " + placementId, String.class);
        String recallStatusOfPlacement = jdbc.queryForObject(
                "SELECT recall_status FROM treatment_placement WHERE id = " + placementId,
                String.class);
        if (confirmStatus == 200) {
            // 先生效后召回：投放被联动标记，链转待补投，不得保持有效可解除状态
            assertEquals("EFFECTIVE", placementStatus);
            assertEquals("RECALLED", recallStatusOfPlacement);
            assertEquals("PENDING_REPLACEMENT", caseStatus(caseId));
        } else {
            // 先召回：被召回批号不得扣库生效
            assertEquals(409, confirmStatus);
            assertEquals("PENDING", placementStatus);
            assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("100.00")));
            assertEquals(0, batchQuantity(1).compareTo(new BigDecimal("60.00")));
        }
    }

    @Test
    void recallRacesWithFinalReleaseConfirmation() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacementWithBatch(caseId, "pl-1", 1L, 1L, 10, "张三");
        confirmPlacement(placementId, "pc-1", "李四", 200);
        clock.setInstant(T0.plus(14, ChronoUnit.DAYS));
        applyRelease(caseId, "ra-1");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<Boolean> releaseFuture = pool.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            int first = mockMvc.perform(MockMvcRequestBuilders
                    .post("/api/cases/" + caseId + "/release-confirmation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            Map.of("requestId", "rcf-1", "side", "DISINFECTION"))))
                    .andReturn().getResponse().getStatus();
            int second = mockMvc.perform(MockMvcRequestBuilders
                    .post("/api/cases/" + caseId + "/release-confirmation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                            Map.of("requestId", "rcf-2", "side", "STORAGE"))))
                    .andReturn().getResponse().getStatus();
            return first == 200 && second == 200;
        });
        Future<Integer> recallFuture = pool.submit(() -> {
            ready.countDown();
            start.await(10, TimeUnit.SECONDS);
            MvcResult result = mockMvc.perform(MockMvcRequestBuilders.post("/api/batches/1/recall")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(Map.of("requestId", "rc-1",
                            "reason", "质量抽检不合格", "operator", "质管员", "expectedVersion", 1))))
                    .andReturn();
            return result.getResponse().getStatus();
        });
        assertTrue(ready.await(10, TimeUnit.SECONDS));
        start.countDown();
        boolean releaseCompleted = releaseFuture.get(30, TimeUnit.SECONDS);
        int recallStatus = recallFuture.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(200, recallStatus);
        String finalStatus = caseStatus(caseId);
        if (releaseCompleted) {
            // 解除先完成：归档链不重开，召回仅追加风险
            assertEquals("RELEASED", finalStatus);
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM recall_impact WHERE impact_type = 'ARCHIVED_RISK'"
                            + " AND case_id = " + caseId, Long.class));
        } else {
            // 召回先生效：不得以被召回投放解除，链转待补投
            assertEquals("PENDING_REPLACEMENT", finalStatus);
            assertNull(jdbc.queryForObject(
                    "SELECT released_at FROM pest_case WHERE id = " + caseId, LocalDateTime.class));
            assertEquals("RECALLED", jdbc.queryForObject(
                    "SELECT recall_status FROM treatment_placement WHERE id = " + placementId,
                    String.class));
        }
    }
}
