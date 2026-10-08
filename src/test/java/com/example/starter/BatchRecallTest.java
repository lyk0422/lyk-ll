package com.example.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 批号召回：原子阻断该批号新投放、在途链转待补投、已解除归档链仅追加风险、
 * 召回不得撤回、expectedVersion 乐观锁与幂等回放。
 */
class BatchRecallTest extends BaseIntegrationTest {

    /**
     * 建立一条以批号 1 投放并生效的在途处置链，返回 [caseId, placementId]。
     */
    private long[] treatedCaseWithBatch() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacementWithBatch(caseId, "pl-1", 1L, 1L, 10, "张三");
        confirmPlacement(placementId, "pc-1", "李四", 200);
        return new long[]{caseId, placementId};
    }

    @Test
    void recallBlocksNewPlacementOfBatch() throws Exception {
        recallBatch(1L, "rc-1", 1).andExpect(status().isOk());

        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");

        // 已召回批号：禁止新投放登记
        postJson("/api/cases/" + caseId + "/placements",
                Map.of("requestId", "pl-1", "pesticideId", 1, "batchId", 1, "dosage", 10,
                        "point", "3排-2架", "registrar", "张三"))
                .andExpect(status().isConflict());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM treatment_placement", Long.class));
        assertEquals(0, batchQuantity(1).compareTo(new BigDecimal("60.00")));
    }

    @Test
    void recallBlocksPendingPlacementConfirm() throws Exception {
        long caseId = reportCase("A", "rpt-1");
        confirmCase(caseId, "cfm-1");
        long placementId = registerPlacementWithBatch(caseId, "pl-1", 1L, 1L, 10, "张三");

        recallBatch(1L, "rc-1", 1).andExpect(status().isOk());

        // 登记后召回：复核生效时事务内重查批号状态，拒绝扣库生效
        confirmPlacement(placementId, "pc-1", "李四", 409);
        assertEquals("PENDING", jdbc.queryForObject(
                "SELECT status FROM treatment_placement WHERE id = " + placementId, String.class));
        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("100.00")));
        assertEquals(0, batchQuantity(1).compareTo(new BigDecimal("60.00")));
    }

    @Test
    void recallMovesInflightChainToPendingReplacement() throws Exception {
        long[] ids = treatedCaseWithBatch();
        long caseId = ids[0];
        long placementId = ids[1];

        MvcResult result = recallBatch(1L, "rc-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECALLED"))
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.impacts[0].placementId").value(placementId))
                .andExpect(jsonPath("$.impacts[0].caseId").value(caseId))
                .andExpect(jsonPath("$.impacts[0].impactType").value("PENDING_REPLACEMENT"))
                .andReturn();
        long recallId = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("recallId").asLong();

        // 在途链转待补投；原投放证据保留但标记召回
        assertEquals("PENDING_REPLACEMENT", caseStatus(caseId));
        assertEquals("EFFECTIVE", jdbc.queryForObject(
                "SELECT status FROM treatment_placement WHERE id = " + placementId, String.class));
        assertEquals("RECALLED", jdbc.queryForObject(
                "SELECT recall_status FROM treatment_placement WHERE id = " + placementId,
                String.class));

        // 观察未有效开始：观察起点为空，不得申请解除
        mockMvc.perform(get("/api/cases/" + caseId + "/observation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.observationStart").doesNotExist())
                .andExpect(jsonPath("$.pendingReplacementPlacementIds[0]").value(placementId));
        postJson("/api/cases/" + caseId + "/release-application",
                Map.of("requestId", "ra-1", "operator", "库房员"))
                .andExpect(status().isConflict());

        // 召回详情可查询对应关系
        mockMvc.perform(get("/api/recalls/" + recallId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.batchId").value(1))
                .andExpect(jsonPath("$.impacts[0].impactType").value("PENDING_REPLACEMENT"));
    }

    @Test
    void recallOnReleasedChainOnlyAppendsRisk() throws Exception {
        long[] ids = treatedCaseWithBatch();
        long caseId = ids[0];
        long placementId = ids[1];
        clock.setInstant(T0.plus(14, ChronoUnit.DAYS));
        applyRelease(caseId, "ra-1");
        confirmRelease(caseId, "rcf-1", "DISINFECTION");
        confirmRelease(caseId, "rcf-2", "STORAGE");
        assertEquals("RELEASED", caseStatus(caseId));

        recallBatch(1L, "rc-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.impacts[0].impactType").value("ARCHIVED_RISK"));

        // 已解除归档链不重开，仅追加风险记录
        assertEquals("RELEASED", caseStatus(caseId));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM recall_impact WHERE impact_type = 'ARCHIVED_RISK'"
                        + " AND case_id = " + caseId + " AND placement_id = " + placementId,
                Long.class));
    }

    @Test
    void recallStaleVersionRejected() throws Exception {
        recallBatch(1L, "rc-1", 2).andExpect(status().isConflict());
        assertEquals("ACTIVE", jdbc.queryForObject(
                "SELECT status FROM pesticide_batch WHERE id = 1", String.class));
    }

    @Test
    void recallCannotBeRepeatedOrWithdrawn() throws Exception {
        recallBatch(1L, "rc-1", 1).andExpect(status().isOk());

        // 重复召回（视为撤回语义）：拒绝，版本已推进
        recallBatch(1L, "rc-2", 1).andExpect(status().isConflict());
        recallBatch(1L, "rc-3", 2).andExpect(status().isConflict());
        assertEquals("RECALLED", jdbc.queryForObject(
                "SELECT status FROM pesticide_batch WHERE id = 1", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_recall", Long.class));
    }

    @Test
    void recallIdempotentReplayAndConflict() throws Exception {
        long[] ids = treatedCaseWithBatch();
        MvcResult first = recallBatch(1L, "rc-1", 1).andExpect(status().isOk()).andReturn();
        String firstBody = first.getResponse().getContentAsString();

        // 同键同参：重放首次响应，不产生新召回事件
        recallBatch(1L, "rc-1", 1)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recallId").value(
                        objectMapper.readTree(firstBody).get("recallId").asLong()));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_recall", Long.class));

        // 同键异参：409
        postJson("/api/batches/1/recall",
                Map.of("requestId", "rc-1", "reason", "另一原因", "operator", "质管员",
                        "expectedVersion", 1))
                .andExpect(status().isConflict());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM batch_recall", Long.class));
        assertEquals(1, jdbc.queryForObject(
                "SELECT COUNT(*) FROM treatment_placement WHERE case_id = " + ids[0]
                        + " AND recall_status = 'RECALLED'", Long.class));
    }

    @Test
    void recallDoesNotRestockOrGeneratePurchaseSuggestion() throws Exception {
        long[] ids = treatedCaseWithBatch();
        // 投放后库存 90.00；召回不退回旧药剂、不算库存补货
        recallBatch(1L, "rc-1", 1).andExpect(status().isOk());

        assertEquals(0, pesticideQuantity(1).compareTo(new BigDecimal("90.00")));
        assertEquals(0, batchQuantity(1).compareTo(new BigDecimal("50.00")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_suggestion", Long.class));
        assertNull(jdbc.queryForObject(
                "SELECT released_at FROM pest_case WHERE id = " + ids[0],
                java.time.LocalDateTime.class));
    }
}
